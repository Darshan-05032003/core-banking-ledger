package com.darshan.ledger.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.ledger.EntryDirection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Statements against a ledger of realistic size: 100 000 balanced journals, 200 000 lines, four
// accounts with 25 000 lines each spread over a year. The data goes in with one SQL
// statement because posting it one journal at a time would take minutes; it is still valid
// double-entry data, so the invariant checks of the other tests keep holding.
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementVolumeTests {

    private static final Logger log = LoggerFactory.getLogger(StatementVolumeTests.class);

    private static final int JOURNALS = 100_000;
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final String SIGNED = "CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END";

    private static final String LOAD_SQL = """
            WITH journals AS (
                INSERT INTO journal_entry (reference, type, description, posted_at, created_by)
                SELECT 'VOL-' || lpad(g::text, 7, '0'),
                       CASE WHEN g % 3 = 0 THEN 'WITHDRAWAL' ELSE 'DEPOSIT' END,
                       'Volume fixture ' || g,
                       :start + g * interval '5 minutes',
                       'fixture'
                FROM generate_series(1, :journals) AS g
                RETURNING id, type, posted_at, substring(reference FROM 5)::int AS n
            ),
            legs AS (
                SELECT id, posted_at,
                       CASE type WHEN 'DEPOSIT' THEN 'CREDIT' ELSE 'DEBIT' END AS customer_direction,
                       CASE type WHEN 'DEPOSIT' THEN 100.00 ELSE 30.00 END   AS amount,
                       (ARRAY[:a0, :a1, :a2, :a3])[(n % 4) + 1]                AS account_id
                FROM journals
            )
            INSERT INTO ledger_entry (journal_entry_id, account_id, gl_account_id, direction, amount, currency, posted_at)
            SELECT id, account_id, NULL, customer_direction, amount, 'INR', posted_at FROM legs
            UNION ALL
            SELECT id, NULL, :vault, CASE customer_direction WHEN 'CREDIT' THEN 'DEBIT' ELSE 'CREDIT' END, amount,
                   'INR', posted_at
            FROM legs
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private StatementRepository repository;

    private long accountId;

    @BeforeAll
    void loadTwoHundredThousandJournals() {
        List<Long> accounts = List.of(testAccounts.openFundedAccount("1000.00"), testAccounts.openFundedAccount("1000.00"),
                testAccounts.openFundedAccount("1000.00"), testAccounts.openFundedAccount("1000.00"));
        accountId = accounts.getFirst();
        Long vaultId = jdbc.queryForObject("select id from gl_account where code = 'CASH_VAULT'", Long.class);

        long started = System.nanoTime();
        int lines = jdbcClient.sql(LOAD_SQL)
                .param("start", START.atOffset(ZoneOffset.UTC))
                .param("journals", JOURNALS)
                .param("a0", accounts.get(0)).param("a1", accounts.get(1))
                .param("a2", accounts.get(2)).param("a3", accounts.get(3))
                .param("vault", vaultId)
                .update();
        for (Long account : accounts) {
            jdbc.update("update account_balance set ledger_balance = s.total, available_balance = s.total,"
                    + " version = version + 1 from (select coalesce(sum(" + SIGNED + "), 0) as total"
                    + " from ledger_entry where account_id = ?) s where account_id = ?", account, account);
        }
        jdbc.execute("vacuum analyze ledger_entry, journal_entry");
        log.info("Loaded {} ledger lines in {} ms", lines, (System.nanoTime() - started) / 1_000_000);
        assertThat(lines).isEqualTo(2 * JOURNALS);
    }

    @Test
    void aStatementPageIsServedByTheAccountPostingIndex() {
        StatementPeriod june = period(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));
        StatementCursor cursor = StatementCursor.after(repository.linesAfter(accountId, june, null, 1000).get(999));

        String plan = explain(StatementRepository.LINES_SQL + StatementRepository.AFTER_CURSOR
                + StatementRepository.ORDER + "LIMIT :limit", Map.of(
                "accountId", accountId, "start", at(june.start()), "end", at(june.end()),
                "cursorPostedAt", at(cursor.postedAt()), "cursorEntryId", cursor.entryId(), "limit", 51));
        log.info("Statement page plan for one month, cursor after 1000 lines:\n{}", plan);

        assertThat(plan).contains("ix_ledger_entry_account_posted_at")
                .doesNotContain("Seq Scan on ledger_entry")
                .doesNotContain("Seq Scan on journal_entry");
        assertThat(plan).containsPattern("Index Cond: \\(\\(account_id = '\\d+'::bigint\\) AND \\(posted_at >= ");
    }

    @Test
    void aPageDeepInAYearLongPeriodStartsFromTheRightRunningBalance() {
        StatementPeriod year = period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        List<StatementLine> firstBlock = repository.linesAfter(accountId, year, null, 20_000);
        StatementLine last = firstBlock.getLast();

        long started = System.nanoTime();
        List<StatementLine> page = repository.linesAfter(accountId, year, StatementCursor.after(last), 50);
        log.info("Page after 20000 lines of a year-long statement in {} ms", (System.nanoTime() - started) / 1_000_000);

        BigDecimal expected = jdbc.queryForObject("select coalesce(sum(" + SIGNED + "), 0) from ledger_entry"
                        + " where account_id = ? and (posted_at, id) <= (?, ?)", BigDecimal.class,
                accountId, at(page.getFirst().postedAt()), page.getFirst().entryId());
        assertThat(page).hasSize(50);
        assertThat(page.getFirst().runningBalance()).isEqualByComparingTo(expected);
        assertThat(page.getFirst().runningBalance()).isEqualByComparingTo(last.runningBalance()
                .add(page.getFirst().direction() == EntryDirection.CREDIT
                        ? page.getFirst().amount() : page.getFirst().amount().negate()));
    }

    @Test
    void aYearOfLinesIsStreamedAsCsvInFull() throws Exception {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        Long expectedLines = jdbc.queryForObject("select count(*) from ledger_entry where account_id = ?"
                + " and posted_at >= ? and posted_at < ?", Long.class, accountId, at(START),
                at(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        BigDecimal closing = jdbc.queryForObject("select coalesce(sum(" + SIGNED + "), 0) from ledger_entry"
                + " where account_id = ? and posted_at < ?", BigDecimal.class, accountId,
                at(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));

        long started = System.nanoTime();
        MvcResult begun = mockMvc.perform(get("/api/v1/accounts/{id}/statement.csv", accountId)
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(begun))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("Streamed a CSV of {} bytes in {} ms", body.length(), (System.nanoTime() - started) / 1_000_000);

        String[] rows = body.split("\r\n");
        assertThat(expectedLines).isGreaterThan(20_000);
        assertThat(rows).hasSize(expectedLines.intValue() + 1);
        assertThat(rows[0]).isEqualTo(StatementCsvWriter.HEADER);
        assertThat(rows[rows.length - 1]).endsWith("," + closing.setScale(2).toPlainString() + ",INR");
    }


    private String explain(String sql, Map<String, Object> params) {
        return String.join("\n", jdbcClient.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
                .params(params)
                .query(String.class)
                .list());
    }

    private static StatementPeriod period(LocalDate from, LocalDate to) {
        return StatementPeriod.of(from, to, Period.ofYears(1), ZoneOffset.UTC);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
