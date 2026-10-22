package com.darshan.ledger.statement;

import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.common.pii.PiiMasker;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.GlAccountCode;
import com.darshan.ledger.ledger.JournalType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Statement reads are plain SQL: the running balance is a window function and the pages are
// keyset ranges, neither of which JPQL expresses. Every amount is signed the way a customer reads
// it: credits raise the balance of a customer account, debits lower it.
@Repository
public class StatementRepository {

    static final int STREAM_FETCH_SIZE = 500;

    private static final String SIGNED_AMOUNT = "CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END";

    private static final String BALANCES_SQL = """
            SELECT COALESCE(SUM(%1$s) FILTER (WHERE posted_at < :start), 0) AS opening,
                   COALESCE(SUM(%1$s), 0)                                   AS closing
            FROM ledger_entry
            WHERE account_id = :accountId
              AND posted_at < :end
            """.formatted(SIGNED_AMOUNT);

    // The window runs over the whole period, so a page that starts in the middle of it still
    // carries the balance of everything before it; the keyset predicate and the limit are applied
    // outside the window, where they cannot shorten what the sum sees.
    private static final String LINES_SQL = """
            WITH opening AS (
                SELECT COALESCE(SUM(%1$s), 0) AS balance
                FROM ledger_entry
                WHERE account_id = :accountId
                  AND posted_at < :start
            ),
            running AS (
                SELECT le.id, le.journal_entry_id, le.posted_at, le.direction, le.amount,
                       opening.balance + SUM(%1$s) OVER (ORDER BY le.posted_at, le.id) AS running_balance
                FROM ledger_entry le
                CROSS JOIN opening
                WHERE le.account_id = :accountId
                  AND le.posted_at >= :start
                  AND le.posted_at < :end
            )
            SELECT r.id, r.posted_at, r.direction, r.amount, r.running_balance,
                   je.id AS journal_id, je.reference, je.type, je.description,
                   cp.account_number AS counterparty_account_number,
                   cp.gl_code        AS counterparty_gl_code
            FROM running r
            JOIN journal_entry je ON je.id = r.journal_entry_id
            LEFT JOIN LATERAL (
                SELECT a.account_number, g.code AS gl_code
                FROM ledger_entry other
                LEFT JOIN account a ON a.id = other.account_id
                LEFT JOIN gl_account g ON g.id = other.gl_account_id
                WHERE other.journal_entry_id = r.journal_entry_id
                  AND other.id <> r.id
                ORDER BY other.id
                LIMIT 1
            ) cp ON TRUE
            """.formatted(SIGNED_AMOUNT);

    private static final String AFTER_CURSOR = "WHERE (r.posted_at, r.id) > (:cursorPostedAt, :cursorEntryId)\n";
    private static final String ORDER = "ORDER BY r.posted_at, r.id\n";

    private static final RowMapper<StatementLine> LINE_MAPPER = StatementRepository::mapLine;

    private final JdbcClient jdbc;

    public StatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    PeriodBalances balances(long accountId, StatementPeriod period) {
        return jdbc.sql(BALANCES_SQL)
                .param("accountId", accountId)
                .param("start", at(period.start()))
                .param("end", at(period.end()))
                .query((rs, rowNum) -> new PeriodBalances(money(rs, "opening"), money(rs, "closing")))
                .single();
    }

    // Asks for one line more than the page so the caller knows whether a next page exists
    // without a count query.
    List<StatementLine> linesAfter(long accountId, StatementPeriod period, StatementCursor cursor, int limit) {
        JdbcClient.StatementSpec spec = jdbc.sql(LINES_SQL + (cursor == null ? "" : AFTER_CURSOR) + ORDER
                        + "LIMIT :limit")
                .param("accountId", accountId)
                .param("start", at(period.start()))
                .param("end", at(period.end()))
                .param("limit", limit);
        if (cursor != null) {
            spec = spec.param("cursorPostedAt", at(cursor.postedAt()))
                    .param("cursorEntryId", cursor.entryId());
        }
        return spec.query(LINE_MAPPER).list();
    }

    // The stream holds a server-side cursor that fetches STREAM_FETCH_SIZE rows at a time, which
    // only works while the surrounding read-only transaction is open: on an auto-commit
    // connection the PostgreSQL driver reads the whole result into memory first. Callers must
    // close the stream to release the cursor.
    Stream<StatementLine> streamLines(long accountId, StatementPeriod period) {
        return jdbc.sql(LINES_SQL + ORDER)
                .param("accountId", accountId)
                .param("start", at(period.start()))
                .param("end", at(period.end()))
                .withFetchSize(STREAM_FETCH_SIZE)
                .query(LINE_MAPPER)
                .stream();
    }

    private static StatementLine mapLine(ResultSet rs, int rowNum) throws SQLException {
        String counterpartyAccount = rs.getString("counterparty_account_number");
        String counterpartyGl = rs.getString("counterparty_gl_code");
        StatementCounterparty counterparty = counterpartyAccount == null && counterpartyGl == null
                ? null
                : new StatementCounterparty(PiiMasker.maskAccountNumber(counterpartyAccount),
                        counterpartyGl == null ? null : GlAccountCode.valueOf(counterpartyGl));
        return new StatementLine(
                rs.getLong("id"),
                rs.getLong("journal_id"),
                rs.getString("reference"),
                JournalType.valueOf(rs.getString("type")),
                rs.getString("description"),
                rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
                EntryDirection.valueOf(rs.getString("direction")),
                money(rs, "amount"),
                counterparty,
                money(rs, "running_balance"));
    }

    // The driver binds OffsetDateTime as timestamptz; the offset is irrelevant for an instant.
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).setScale(Money.SCALE, Money.ROUNDING);
    }
}
