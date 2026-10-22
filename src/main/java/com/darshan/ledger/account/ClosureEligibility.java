package com.darshan.ledger.account;

import com.darshan.ledger.common.money.Money;

public sealed interface ClosureEligibility {

    record Eligible() implements ClosureEligibility {
    }

    record FundsOnHold(Money held) implements ClosureEligibility {
    }

    record BalanceNotZero(Money balance) implements ClosureEligibility {
    }
}
