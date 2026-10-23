package com.darshan.ledger.ledger;

import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.common.pii.PiiMasker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// The reconciliation queries. Each one is a single pass in SQL: the set of balances that
// disagree with their lines, the set of journals whose legs do not add up, and the grand totals.
// count(*) OVER () carries the full number of offenders alongside a capped page of them, so one
// statement answers both "how many" and "which".
@Repository
class EndOfDayCheckRepository {

    record MismatchPage(List<BalanceMismatch> rows, long total) {
    }

    record UnbalancedPage(List<UnbalancedJournal> rows, long total) {
    }

    private static final String SIGNED_AMOUNT = "CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END";

    private static final String BALANCE_MISMATCHES_SQL = """
            SELECT b.account_id, a.account_number, b.currency, b.ledger_balance, b.available_balance,
                   COALESCE(lines.balance, 0)                   AS expected_ledger,
                   b.ledger_balance - COALESCE(holds.held, 0)   AS expected_available,
                   count(*) OVER ()                             AS total
            FROM account_balance b
            JOIN account a ON a.id = b.account_id
            LEFT JOIN (
                SELECT account_id, SUM(%s) AS balance
                FROM ledger_entry
                WHERE account_id IS NOT NULL
                GROUP BY account_id
            ) lines ON lines.account_id = b.account_id
            LEFT JOIN (
                SELECT account_id, SUM(amount) AS held
                FROM hold
                WHERE status = 'ACTIVE'
                GROUP BY account_id
            ) holds ON holds.account_id = b.account_id
            WHERE b.ledger_balance <> COALESCE(lines.balance, 0)
               OR b.available_balance <> b.ledger_balance - COALESCE(holds.held, 0)
            ORDER BY b.account_id
            LIMIT :limit
            """.formatted(SIGNED_AMOUNT);

    private static final String UNBALANCED_JOURNALS_SQL = """
            SELECT je.id, je.reference, count(le.id) AS legs,
                   COALESCE(SUM(le.amount) FILTER (WHERE le.direction = 'DEBIT'), 0)  AS debits,
                   COALESCE(SUM(le.amount) FILTER (WHERE le.direction = 'CREDIT'), 0) AS credits,
                   count(*) OVER ()                                                   AS total
            FROM journal_entry je
            LEFT JOIN ledger_entry le ON le.journal_entry_id = je.id
            GROUP BY je.id, je.reference
            HAVING COALESCE(SUM(le.amount) FILTER (WHERE le.direction = 'DEBIT'), 0)
                   <> COALESCE(SUM(le.amount) FILTER (WHERE le.direction = 'CREDIT'), 0)
                OR count(le.id) < 2
            ORDER BY je.id
            LIMIT :limit
            """;

    private final JdbcClient jdbc;

    EndOfDayCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long countBalances() {
        return jdbc.sql("SELECT count(*) FROM account_balance").query(Long.class).single();
    }

    long countJournals() {
        return jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single();
    }

    MismatchPage balanceMismatches(int limit) {
        long[] total = new long[1];
        List<BalanceMismatch> rows = jdbc.sql(BALANCE_MISMATCHES_SQL)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    total[0] = rs.getLong("total");
                    return mismatch(rs);
                })
                .list();
        return new MismatchPage(rows, total[0]);
    }

    UnbalancedPage unbalancedJournals(int limit) {
        long[] total = new long[1];
        List<UnbalancedJournal> rows = jdbc.sql(UNBALANCED_JOURNALS_SQL)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    total[0] = rs.getLong("total");
                    return new UnbalancedJournal(rs.getLong("id"), rs.getString("reference"), rs.getInt("legs"),
                            money(rs, "debits"), money(rs, "credits"));
                })
                .list();
        return new UnbalancedPage(rows, total[0]);
    }

    List<CurrencyTotals> currencyTotals() {
        return jdbc.sql("""
                SELECT currency,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                FROM ledger_entry
                GROUP BY currency
                ORDER BY currency
                """)
                .query((rs, rowNum) -> new CurrencyTotals(rs.getString("currency"), money(rs, "debits"),
                        money(rs, "credits")))
                .list();
    }

    // The full account number never leaves the query; the report, the log and the audit row all
    // get the masked form.
    private static BalanceMismatch mismatch(ResultSet rs) throws SQLException {
        return new BalanceMismatch(rs.getLong("account_id"), PiiMasker.maskAccountNumber(rs.getString("account_number")),
                rs.getString("currency"), money(rs, "expected_ledger"), money(rs, "ledger_balance"),
                money(rs, "expected_available"), money(rs, "available_balance"));
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }
}
