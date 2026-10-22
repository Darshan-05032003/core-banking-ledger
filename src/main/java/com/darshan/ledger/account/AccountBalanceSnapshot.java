package com.darshan.ledger.account;

import com.darshan.ledger.common.money.CurrencyMismatchException;
import com.darshan.ledger.common.money.Money;
import java.util.Currency;
import java.util.Objects;

// Ledger balance is what the books say the customer owns; available balance is what they can
// spend right now. The difference is money reserved by pending holds.
public record AccountBalanceSnapshot(Money ledgerBalance, Money availableBalance) {

    public AccountBalanceSnapshot {
        Objects.requireNonNull(ledgerBalance, "ledger balance must not be null");
        Objects.requireNonNull(availableBalance, "available balance must not be null");
        if (!ledgerBalance.hasCurrency(availableBalance.currency())) {
            throw new CurrencyMismatchException(ledgerBalance.currency(), availableBalance.currency());
        }
    }

    public static AccountBalanceSnapshot empty(Currency currency) {
        return new AccountBalanceSnapshot(Money.zero(currency), Money.zero(currency));
    }

    public Money heldAmount() {
        return ledgerBalance.minus(availableBalance);
    }

    public ClosureEligibility closureEligibility() {
        Money held = heldAmount();
        if (held.isPositive()) {
            return new ClosureEligibility.FundsOnHold(held);
        }
        if (!ledgerBalance.isZero()) {
            return new ClosureEligibility.BalanceNotZero(ledgerBalance);
        }
        return new ClosureEligibility.Eligible();
    }
}
