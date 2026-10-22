package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.account.ClosureEligibility;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.money.CurrencyMismatchException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccountBalanceTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private AccountBalance balance;

    @BeforeEach
    void setUp() {
        Customer customer = new Customer("Asha Rao", "asha@example.com", "+919876543210", LocalDate.of(1990, 5, 17), NOW);
        Account account = new Account(AccountNumber.of("0001", 1), customer, AccountType.SAVINGS, INR, TODAY, NOW);
        ReflectionTestUtils.setField(account, "id", 10L);
        balance = new AccountBalance(account);
    }

    @Test
    void startsAtZeroInTheAccountCurrency() {
        assertThat(balance.getAccountId()).isEqualTo(10L);
        assertThat(balance.ledgerBalance()).isEqualTo(Money.zero(INR));
        assertThat(balance.availableBalance()).isEqualTo(Money.zero(INR));
        assertThat(balance.snapshot().closureEligibility()).isInstanceOf(ClosureEligibility.Eligible.class);
    }

    @Test
    void creditAndDebitMoveBothBalancesTogether() {
        balance.credit(Money.of("500.00", INR));
        balance.debit(Money.of("120.25", INR));

        assertThat(balance.ledgerBalance()).isEqualTo(Money.of("379.75", INR));
        assertThat(balance.availableBalance()).isEqualTo(Money.of("379.75", INR));
    }

    @Test
    void debitBeyondTheAvailableBalanceIsRefusedAndChangesNothing() {
        balance.credit(Money.of("100.00", INR));

        assertThatThrownBy(() -> balance.debit(Money.of("100.01", INR)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("100.00 INR is lower than 100.01 INR")
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
        assertThat(balance.ledgerBalance()).isEqualTo(Money.of("100.00", INR));
        assertThat(balance.availableBalance()).isEqualTo(Money.of("100.00", INR));
    }

    @Test
    void debitOfTheExactAvailableBalanceIsAllowed() {
        balance.credit(Money.of("100.00", INR));
        balance.debit(Money.of("100.00", INR));

        assertThat(balance.ledgerBalance().isZero()).isTrue();
    }

    @Test
    void refusesAmountsInAnotherCurrency() {
        assertThatThrownBy(() -> balance.credit(Money.of("1.00", USD))).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> balance.debit(Money.of("1.00", USD))).isInstanceOf(CurrencyMismatchException.class);
    }
}
