package com.darshan.ledger.ledger;

import static com.darshan.ledger.FixedClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.audit.AuditLog;
import com.darshan.ledger.audit.AuditLogRepository;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// The checks are global, so every corruption a test plants is undone before the test ends: the
// balance rows are put back by an UPDATE, and the one-sided ledger line is inserted inside a
// transaction that is rolled back, because the append-only trigger refuses to delete it.
@IntegrationTest
class EndOfDayCheckIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private EndOfDayCheck check;

    @Autowired
    private EndOfDayCheckJob job;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        checkLogger().addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        checkLogger().detachAppender(logs);
        logs.stop();
    }

    @Test
    void aHealthyLedgerPassesWithoutErrorsMetricsOrFailureRows() {
        testAccounts.openFundedAccount("1000.00");
        double balanceCount = mismatches("balance");
        double journalCount = mismatches("journal");
        double totalsCount = mismatches("totals");

        EndOfDayCheckResult result = check.run(TODAY);

        assertThat(result.passed()).isTrue();
        assertThat(result.businessDate()).isEqualTo(TODAY);
        assertThat(result.accountsChecked()).isGreaterThanOrEqualTo(1);
        assertThat(result.balanceMismatches()).isZero();
        assertThat(result.mismatchedBalances()).isEmpty();
        assertThat(result.journalsChecked()).isGreaterThanOrEqualTo(1);
        assertThat(result.unbalancedJournals()).isZero();
        assertThat(result.totals()).isNotEmpty().allSatisfy(totals -> {
            assertThat(totals.balanced()).isTrue();
            assertThat(totals.debits()).isPositive();
        });
        assertThat(result.totalsBalanced()).isTrue();
        assertThat(errorLogs()).isEmpty();
        assertThat(mismatches("balance")).isEqualTo(balanceCount);
        assertThat(mismatches("journal")).isEqualTo(journalCount);
        assertThat(mismatches("totals")).isEqualTo(totalsCount);
    }

    @Test
    void aCorruptedLedgerBalanceIsReportedLoggedCountedAndAudited() {
        long accountId = testAccounts.openFundedAccount("1000.00");
        String accountNumber = testAccounts.accountNumber(accountId);
        String masked = "XXXXXXXX" + accountNumber.substring(8);
        double before = mismatches("balance");

        jdbc.update("update account_balance set ledger_balance = ledger_balance + 100 where account_id = ?", accountId);
        try {
            EndOfDayCheckResult result = check.run(TODAY);

            assertThat(result.passed()).isFalse();
            assertThat(result.balanceMismatches()).isEqualTo(1);
            BalanceMismatch mismatch = result.mismatchedBalances().getFirst();
            assertThat(mismatch.accountId()).isEqualTo(accountId);
            assertThat(mismatch.maskedAccountNumber()).isEqualTo(masked);
            assertThat(mismatch.currency()).isEqualTo("INR");
            assertThat(mismatch.expectedLedgerBalance()).isEqualTo(new BigDecimal("1000.00"));
            assertThat(mismatch.actualLedgerBalance()).isEqualTo(new BigDecimal("1100.00"));
            assertThat(mismatch.expectedAvailableBalance()).isEqualTo(new BigDecimal("1100.00"));
            assertThat(mismatch.actualAvailableBalance()).isEqualTo(new BigDecimal("1000.00"));
            assertThat(result.unbalancedJournals()).isZero();
            assertThat(result.totalsBalanced()).isTrue();

            assertThat(errorLogs()).anySatisfy(line -> assertThat(line)
                    .contains(masked)
                    .contains("ledger balance 1100.00")
                    .contains("lines sum to 1000.00")
                    .doesNotContain(accountNumber));
            assertThat(mismatches("balance")).isEqualTo(before + 1);

            AuditLog row = auditRows("ACCOUNT", accountId).getLast();
            assertThat(row.getAction()).isEqualTo("EOD_BALANCE_MISMATCH");
            assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(row.getErrorCode()).isEqualTo("BALANCE_MISMATCH");
            assertThat(row.getActor()).isEqualTo("user");
            assertThat(row.getRoles()).isEqualTo("ADMIN");
            assertThat(jsonb(row))
                    .contains("\"account\": \"" + masked + "\"")
                    .contains("\"expectedLedgerBalance\": 1000.00")
                    .contains("\"actualLedgerBalance\": 1100.00")
                    .contains("\"expectedAvailableBalance\": 1100.00")
                    .contains("\"actualAvailableBalance\": 1000.00")
                    .doesNotContain(accountNumber);
        } finally {
            jdbc.update("update account_balance set ledger_balance = ledger_balance - 100 where account_id = ?",
                    accountId);
        }

        assertThat(check.run(TODAY).passed()).isTrue();
    }

    @Test
    void anAvailableBalanceThatDoesNotMatchTheHoldsIsReported() {
        long accountId = testAccounts.openFundedAccount("1000.00");

        jdbc.update("update account_balance set available_balance = 900.00 where account_id = ?", accountId);
        try {
            EndOfDayCheckResult result = check.run(TODAY);

            assertThat(result.passed()).isFalse();
            assertThat(result.mismatchedBalances()).singleElement().satisfies(mismatch -> {
                assertThat(mismatch.accountId()).isEqualTo(accountId);
                assertThat(mismatch.expectedLedgerBalance()).isEqualTo(new BigDecimal("1000.00"));
                assertThat(mismatch.actualLedgerBalance()).isEqualTo(new BigDecimal("1000.00"));
                assertThat(mismatch.expectedAvailableBalance()).isEqualTo(new BigDecimal("1000.00"));
                assertThat(mismatch.actualAvailableBalance()).isEqualTo(new BigDecimal("900.00"));
            });
        } finally {
            jdbc.update("update account_balance set available_balance = 1000.00 where account_id = ?", accountId);
        }
    }

    @Test
    void aOneSidedLegWrittenBehindThePostingServiceBreaksTheJournalAndTheTotals() {
        long accountId = testAccounts.openFundedAccount("1000.00");
        long journalId = jdbc.queryForObject("select journal_entry_id from ledger_entry where account_id = ?",
                Long.class, accountId);
        String reference = jdbc.queryForObject("select reference from journal_entry where id = ?", String.class,
                journalId);
        double journalsBefore = mismatches("journal");
        double totalsBefore = mismatches("totals");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("""
                    insert into ledger_entry (journal_entry_id, account_id, direction, amount, currency, posted_at)
                    select id, ?, 'CREDIT', 1.00, 'INR', posted_at from journal_entry where id = ?
                    """, accountId, journalId);

            EndOfDayCheckResult result = check.run(TODAY);

            assertThat(result.passed()).isFalse();
            assertThat(result.unbalancedJournals()).isEqualTo(1);
            assertThat(result.unbalanced()).singleElement().satisfies(journal -> {
                assertThat(journal.journalId()).isEqualTo(journalId);
                assertThat(journal.reference()).isEqualTo(reference);
                assertThat(journal.legs()).isEqualTo(3);
                assertThat(journal.debits()).isEqualTo(new BigDecimal("1000.00"));
                assertThat(journal.credits()).isEqualTo(new BigDecimal("1001.00"));
            });
            assertThat(result.totalsBalanced()).isFalse();
            assertThat(result.totals()).filteredOn(totals -> totals.currency().equals("INR")).singleElement()
                    .satisfies(inr -> assertThat(inr.credits().subtract(inr.debits())).isEqualByComparingTo("1.00"));
            assertThat(result.balanceMismatches()).isEqualTo(1);
            assertThat(result.mismatchedBalances().getFirst().expectedLedgerBalance())
                    .isEqualTo(new BigDecimal("1001.00"));

            assertThatThrownBy(() -> jdbc.update("delete from ledger_entry where journal_entry_id = ? and amount = 1.00",
                    journalId)).isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });

        assertThat(errorLogs()).anySatisfy(line -> assertThat(line)
                .contains("journal " + reference).contains("3 legs").contains("debits 1000.00").contains("credits 1001.00"));
        assertThat(mismatches("journal")).isEqualTo(journalsBefore + 1);
        assertThat(mismatches("totals")).isEqualTo(totalsBefore + 1);
        AuditLog journalRow = auditRows("JOURNAL", journalId).getLast();
        assertThat(journalRow.getAction()).isEqualTo("EOD_JOURNAL_UNBALANCED");
        assertThat(journalRow.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(journalRow.getErrorCode()).isEqualTo("UNBALANCED_JOURNAL");
        assertThat(jsonb(journalRow)).contains("\"reference\": \"" + reference + "\"").contains("\"credits\": 1001.00");
        AuditLog totalsRow = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdAsc("LEDGER", "INR").getLast();
        assertThat(totalsRow.getAction()).isEqualTo("EOD_TOTALS_MISMATCH");
        assertThat(totalsRow.getErrorCode()).isEqualTo("LEDGER_OUT_OF_BALANCE");
        assertThat(check.run(TODAY).passed()).isTrue();
    }

    @Test
    void theScheduledRunClosesYesterdayUnderTheShedLockAndLeavesASummaryRow() {
        testAccounts.openFundedAccount("10.00");

        job.run();

        String lockedBy = jdbc.queryForObject("select locked_by from shedlock where name = ?", String.class,
                EndOfDayCheckJob.LOCK_NAME);
        OffsetDateTime lockUntil = jdbc.queryForObject("select lock_until from shedlock where name = ?",
                OffsetDateTime.class, EndOfDayCheckJob.LOCK_NAME);
        assertThat(lockedBy).isNotBlank();
        assertThat(lockUntil).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
        AuditLog row = auditLogRepository
                .findByTargetTypeAndTargetIdOrderByIdAsc("BUSINESS_DATE", TODAY.minusDays(1).toString()).stream()
                .filter(candidate -> candidate.getAction().equals("EOD_CHECK"))
                .toList().getLast();
        assertThat(row.getActor()).isEqualTo("system");
        assertThat(row.getRoles()).isEmpty();
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getErrorCode()).isNull();
        assertThat(jsonb(row)).contains("\"accountsChecked\": ").contains("\"balanceMismatches\": 0")
                .contains("\"totalsBalanced\": true");
    }

    @Test
    void anAdministratorCanRunTheCheckForTodayAndTheRunIsAudited() throws Exception {
        TestUser admin = testUsers.create(Role.ADMIN);
        String correlationId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/admin/jobs/eod-check")
                        .with(testUsers.bearer(admin))
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.balanceMismatches").value(0))
                .andExpect(jsonPath("$.totals[0].currency").value("INR"));

        List<AuditLog> rows = auditLogRepository.findByCorrelationIdOrderByIdAsc(correlationId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getAction()).isEqualTo("EOD_CHECK_RUN");
        assertThat(rows.getFirst().getActor()).isEqualTo(admin.username());
        assertThat(rows.getFirst().getTargetType()).isEqualTo("BUSINESS_DATE");
        assertThat(rows.getFirst().getTargetId()).isEqualTo(TODAY.toString());
        assertThat(rows.getFirst().getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(jsonb(rows.getFirst())).contains("\"passed\": true");

        mockMvc.perform(post("/api/v1/admin/jobs/eod-check").param("date", TODAY.plusDays(1).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_DATE_IN_FUTURE"));
    }

    private double mismatches(String type) {
        return meterRegistry.get(EndOfDayCheck.MISMATCH_METRIC).tag("type", type).counter().count();
    }

    private List<String> errorLogs() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<AuditLog> auditRows(String targetType, long targetId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByIdAsc(targetType, String.valueOf(targetId));
    }

    private String jsonb(AuditLog row) {
        return jdbc.queryForObject("select details::text from audit_log where id = ?", String.class, row.getId());
    }

    private static Logger checkLogger() {
        return (Logger) LoggerFactory.getLogger(EndOfDayCheck.class);
    }
}
