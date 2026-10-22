package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import java.math.BigDecimal;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// The rules the database enforces on its own, exercised with raw SQL so no application code
// stands between the test and the constraint.
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerSchemaTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TellerService tellerService;

    private long accountId;
    private long journalId;

    @BeforeAll
    void postOneDeposit() {
        accountId = testAccounts.openActiveAccount();
        journalId = tellerService.deposit(accountId, new CashRequest(new BigDecimal("100.00"), "INR", null))
                .journal().id();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update journal_entry set description = 'tampered' where id = ?",
            "delete from journal_entry where id = ?",
            "update ledger_entry set amount = amount + 1 where journal_entry_id = ?",
            "delete from ledger_entry where journal_entry_id = ?"})
    void postedJournalsAndLegsCannotBeChangedOrDeleted(String statement) {
        assertThatThrownBy(() -> jdbc.update(statement, journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only")
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23000"));

        assertThat(jdbc.queryForObject("select description from journal_entry where id = ?", String.class, journalId))
                .isEqualTo("Cash deposit");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where journal_entry_id = ?", Long.class,
                journalId)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncate table ledger_entry", "truncate table journal_entry cascade"})
    void ledgerTablesCannotBeTruncated(String statement) {
        assertThatThrownBy(() -> jdbc.execute(statement))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void aLegMustPointAtExactlyOneAccount() {
        String insert = "insert into ledger_entry"
                + " (journal_entry_id, account_id, gl_account_id, direction, amount, currency, posted_at)"
                + " values (?, ?, ?, 'DEBIT', 1.00, 'INR', (select posted_at from journal_entry where id = ?))";
        Long vaultId = jdbc.queryForObject("select id from gl_account where code = 'CASH_VAULT'", Long.class);

        assertThatThrownBy(() -> jdbc.update(insert, journalId, null, null, journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_one_account");
        assertThatThrownBy(() -> jdbc.update(insert, journalId, accountId, vaultId, journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_one_account");
    }

    @Test
    void aLegCannotCarryAPostingTimeOtherThanItsJournals() {
        String insert = "insert into ledger_entry"
                + " (journal_entry_id, account_id, direction, amount, currency, posted_at)"
                + " values (?, ?, 'CREDIT', 1.00, 'INR', (select posted_at + interval '1 second' from journal_entry"
                + " where id = ?))";

        assertThatThrownBy(() -> jdbc.update(insert, journalId, accountId, journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_ledger_entry_journal_posted_at");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry le join journal_entry je"
                + " on je.id = le.journal_entry_id where le.posted_at <> je.posted_at", Long.class)).isZero();
    }

    @Test
    void aJournalCanBeReversedOnlyOnceEvenIfTwoWritersRace() {
        String insert = "insert into journal_entry (reference, type, description, posted_at, created_by, reversal_of)"
                + " values (?, 'REVERSAL', 'test', now(), 'test', ?)";
        jdbc.update(insert, "TEST-REV-1", journalId);

        assertThatThrownBy(() -> jdbc.update(insert, "TEST-REV-2", journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_journal_entry_reversal_of");
    }

    @Test
    void onlyReversalsCarryAReversalLink() {
        String insert = "insert into journal_entry (reference, type, description, posted_at, created_by, reversal_of)"
                + " values (?, ?, 'test', now(), 'test', ?)";

        assertThatThrownBy(() -> jdbc.update(insert, "TEST-BAD-1", "DEPOSIT", journalId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_journal_entry_reversal");
        assertThatThrownBy(() -> jdbc.update(insert, "TEST-BAD-2", "REVERSAL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_journal_entry_reversal");
    }

    @Test
    void balancesCanNeverGoNegativeOrExceedTheLedgerBalance() {
        assertThatThrownBy(() -> jdbc.update(
                "update account_balance set available_balance = -0.01 where account_id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_balance_available_not_negative");
        assertThatThrownBy(() -> jdbc.update(
                "update account_balance set available_balance = ledger_balance + 0.01 where account_id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_balance_available_within_ledger");
    }

    @Test
    void everyAccountHasExactlyOneBalanceRowInItsOwnCurrency() {
        Long accountsWithoutBalance = jdbc.queryForObject(
                "select count(*) from account a left join account_balance b on b.account_id = a.id"
                        + " where b.account_id is null or b.currency <> a.currency", Long.class);

        assertThat(accountsWithoutBalance).isZero();
    }
}
