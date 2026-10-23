package com.darshan.ledger.interest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Plain SQL: the balances are an aggregate over ledger lines, the insert relies on ON CONFLICT,
// and the month-end read locks rows. None of that is an entity anyone edits afterwards.
@Repository
class InterestAccrualRepository {

    record EndOfDayBalance(long accountId, Currency currency, BigDecimal balance) {
    }

    record UnpostedAccruals(int count, BigDecimal total) {
    }

    // The balance at the end of the accrual date is the sum of the account's lines posted before
    // the next day began, not the balance row: a run that happens late, after further postings,
    // must still see the day as it ended. The lateral aggregate uses the statement index on
    // (account_id, posted_at, id) once per account, and the keyset on a.id keeps batches cheap
    // however many accounts there are.
    private static final String END_OF_DAY_BALANCES_SQL = """
            SELECT a.id, a.currency, COALESCE(lines.balance, 0) AS balance
            FROM account a
            LEFT JOIN LATERAL (
                SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS balance
                FROM ledger_entry
                WHERE account_id = a.id
                  AND posted_at < :cutoff
            ) lines ON TRUE
            WHERE a.type = 'SAVINGS'
              AND a.status <> 'CLOSED'
              AND a.opened_on <= :accrualDate
              AND a.id > :afterAccountId
            ORDER BY a.id
            LIMIT :limit
            """;

    // FOR UPDATE on the rows about to be paid out: a second poster for the same account waits
    // here, then re-reads under READ COMMITTED and finds the rows already carry a journal id.
    private static final String LOCK_UNPOSTED_SQL = """
            SELECT count(*) AS row_count, COALESCE(SUM(amount), 0) AS total
            FROM (
                SELECT amount
                FROM interest_accrual
                WHERE account_id = :accountId
                  AND accrual_date <= :upTo
                  AND posted_journal_id IS NULL
                FOR UPDATE
            ) unposted
            """;

    private final JdbcClient jdbc;

    InterestAccrualRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<EndOfDayBalance> endOfDayBalances(LocalDate accrualDate, Instant cutoff, long afterAccountId, int limit) {
        return jdbc.sql(END_OF_DAY_BALANCES_SQL)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("accrualDate", accrualDate)
                .param("afterAccountId", afterAccountId)
                .param("limit", limit)
                .query((rs, rowNum) -> new EndOfDayBalance(rs.getLong("id"),
                        Currency.getInstance(rs.getString("currency")), rs.getBigDecimal("balance")))
                .list();
    }

    // Returns false when the day was already accrued for this account. The unique key decides,
    // so two runs racing on the same day cannot both insert, and nothing is read first.
    boolean insert(long accountId, LocalDate accrualDate, BigDecimal balance, BigDecimal annualRate,
            BigDecimal amount, Currency currency, Instant createdAt) {
        int inserted = jdbc.sql("""
                INSERT INTO interest_accrual (account_id, accrual_date, balance, annual_rate, amount, currency,
                                              created_at)
                VALUES (:accountId, :accrualDate, :balance, :annualRate, :amount, :currency, :createdAt)
                ON CONFLICT (account_id, accrual_date) DO NOTHING
                """)
                .param("accountId", accountId)
                .param("accrualDate", accrualDate)
                .param("balance", balance)
                .param("annualRate", annualRate)
                .param("amount", amount)
                .param("currency", currency.getCurrencyCode())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    List<Long> accountIdsWithUnpostedAccrualsUpTo(LocalDate upTo) {
        return jdbc.sql("""
                SELECT DISTINCT account_id
                FROM interest_accrual
                WHERE accrual_date <= :upTo
                  AND posted_journal_id IS NULL
                ORDER BY account_id
                """)
                .param("upTo", upTo)
                .query(Long.class)
                .list();
    }

    UnpostedAccruals lockUnpostedUpTo(long accountId, LocalDate upTo) {
        return jdbc.sql(LOCK_UNPOSTED_SQL)
                .param("accountId", accountId)
                .param("upTo", upTo)
                .query((rs, rowNum) -> new UnpostedAccruals(rs.getInt("row_count"), rs.getBigDecimal("total")))
                .single();
    }

    int markPosted(long accountId, LocalDate upTo, long journalId) {
        return jdbc.sql("""
                UPDATE interest_accrual
                SET posted_journal_id = :journalId
                WHERE account_id = :accountId
                  AND accrual_date <= :upTo
                  AND posted_journal_id IS NULL
                """)
                .param("journalId", journalId)
                .param("accountId", accountId)
                .param("upTo", upTo)
                .update();
    }
}
