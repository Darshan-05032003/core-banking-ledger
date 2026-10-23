package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// The accounting identity must survive any sequence of operations, including the ones that are
// refused: every journal balances, every balance row equals its account's history, and what the
// bank owes its customers equals the cash in the vault plus the interest it has paid them.
@IntegrationTest
class LedgerInvariantTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final int OPERATIONS = 200;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TellerService tellerService;

    @Autowired
    private JournalService journalService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void booksStayBalancedAfterRandomDepositsWithdrawalsAndReversals() {
        Random random = new Random(20261022L);
        List<Long> accounts = List.of(testAccounts.openActiveAccount(), testAccounts.openActiveAccount(),
                testAccounts.openActiveAccount());
        Map<Long, Money> expected = new HashMap<>();
        accounts.forEach(id -> expected.put(id, Money.zero(INR)));
        List<Long> reversible = new ArrayList<>();
        int refused = 0;
        int reversals = 0;

        for (int i = 0; i < OPERATIONS; i++) {
            long accountId = accounts.get(random.nextInt(accounts.size()));
            Money amount = Money.of(BigDecimal.valueOf(random.nextInt(1, 100_000), 2), INR);
            try {
                switch (random.nextInt(3)) {
                    case 0 -> {
                        reversible.add(tellerService.deposit(accountId, request(amount)).journal().id());
                        expected.merge(accountId, amount, Money::plus);
                    }
                    case 1 -> {
                        reversible.add(tellerService.withdraw(accountId, request(amount)).journal().id());
                        expected.merge(accountId, amount, Money::minus);
                    }
                    default -> {
                        if (reversible.isEmpty()) {
                            continue;
                        }
                        long journalId = reversible.remove(random.nextInt(reversible.size()));
                        JournalResponse original = journalService.get(journalId);
                        journalService.reverse(journalId, new ReversalRequest("Random reversal"));
                        reversals++;
                        LedgerEntryResponse customerLeg = original.entries().stream()
                                .filter(entry -> entry.accountId() != null).findFirst().orElseThrow();
                        Money legAmount = Money.of(customerLeg.amount(), INR);
                        expected.merge(customerLeg.accountId(), legAmount,
                                customerLeg.direction() == EntryDirection.CREDIT ? Money::minus : Money::plus);
                    }
                }
            } catch (LedgerException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
                refused++;
            }
        }

        assertThat(refused).as("the random walk should hit insufficient funds at least once").isPositive();
        assertThat(reversals).isPositive();
        for (Long accountId : accounts) {
            BigDecimal ledger = jdbc.queryForObject(
                    "select ledger_balance from account_balance where account_id = ?", BigDecimal.class, accountId);
            BigDecimal available = jdbc.queryForObject(
                    "select available_balance from account_balance where account_id = ?", BigDecimal.class, accountId);
            BigDecimal fromHistory = jdbc.queryForObject(
                    "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)"
                            + " from ledger_entry where account_id = ?", BigDecimal.class, accountId);
            assertThat(ledger).isEqualByComparingTo(expected.get(accountId).amount());
            assertThat(available).isEqualByComparingTo(ledger);
            assertThat(fromHistory).isEqualByComparingTo(ledger);
            assertThat(ledger).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
        assertSystemWideInvariants();
    }

    private void assertSystemWideInvariants() {
        BigDecimal debitsMinusCredits = jdbc.queryForObject(
                "select coalesce(sum(case when direction = 'DEBIT' then amount else -amount end), 0) from ledger_entry",
                BigDecimal.class);
        assertThat(debitsMinusCredits).isZero();

        List<Long> unbalancedJournals = jdbc.queryForList(
                "select journal_entry_id from ledger_entry group by journal_entry_id"
                        + " having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0", Long.class);
        assertThat(unbalancedJournals).isEmpty();

        BigDecimal cashInVault = jdbc.queryForObject(
                "select coalesce(sum(case when e.direction = 'DEBIT' then e.amount else -e.amount end), 0)"
                        + " from ledger_entry e join gl_account g on g.id = e.gl_account_id where g.code = 'CASH_VAULT'",
                BigDecimal.class);
        BigDecimal interestPaid = jdbc.queryForObject(
                "select coalesce(sum(case when e.direction = 'DEBIT' then e.amount else -e.amount end), 0)"
                        + " from ledger_entry e join gl_account g on g.id = e.gl_account_id"
                        + " where g.code = 'INTEREST_EXPENSE'",
                BigDecimal.class);
        BigDecimal owedToCustomers = jdbc.queryForObject(
                "select coalesce(sum(ledger_balance), 0) from account_balance", BigDecimal.class);
        BigDecimal customerHistory = jdbc.queryForObject(
                "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)"
                        + " from ledger_entry where account_id is not null", BigDecimal.class);
        assertThat(cashInVault.add(interestPaid)).isEqualByComparingTo(owedToCustomers);
        assertThat(customerHistory).isEqualByComparingTo(owedToCustomers);
    }

    private static CashRequest request(Money amount) {
        return new CashRequest(amount.amount(), amount.currency().getCurrencyCode(), null);
    }
}
