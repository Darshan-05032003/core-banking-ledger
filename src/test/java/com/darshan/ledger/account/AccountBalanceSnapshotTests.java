package com.darshan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.common.money.CurrencyMismatchException;
import com.darshan.ledger.common.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class AccountBalanceSnapshotTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void emptyAccountIsEligibleForClosure() {
        assertThat(AccountBalanceSnapshot.empty(INR).closureEligibility())
                .isEqualTo(new ClosureEligibility.Eligible());
    }

    @Test
    void holdsBlockClosureEvenWhenTheyExplainTheWholeBalance() {
        AccountBalanceSnapshot snapshot = new AccountBalanceSnapshot(Money.of("100.00", INR), Money.of("60.00", INR));

        assertThat(snapshot.heldAmount()).isEqualTo(Money.of("40.00", INR));
        assertThat(snapshot.closureEligibility()).isEqualTo(new ClosureEligibility.FundsOnHold(Money.of("40.00", INR)));
    }

    @Test
    void nonZeroBalanceWithoutHoldsBlocksClosure() {
        AccountBalanceSnapshot snapshot = new AccountBalanceSnapshot(Money.of("0.01", INR), Money.of("0.01", INR));

        assertThat(snapshot.closureEligibility()).isEqualTo(new ClosureEligibility.BalanceNotZero(Money.of("0.01", INR)));
    }

    @Test
    void overdrawnBalanceAlsoBlocksClosure() {
        AccountBalanceSnapshot snapshot = new AccountBalanceSnapshot(Money.of("-5.00", INR), Money.of("-5.00", INR));

        assertThat(snapshot.closureEligibility()).isInstanceOf(ClosureEligibility.BalanceNotZero.class);
    }

    @Test
    void bothBalancesMustShareACurrency() {
        assertThatThrownBy(() -> new AccountBalanceSnapshot(Money.zero(INR), Money.zero(USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
