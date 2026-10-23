package com.darshan.ledger.ledger;

import java.math.BigDecimal;

// The sum of every debit and every credit ever posted in one currency. In a sound ledger the
// two are equal, because every journal that was accepted was balanced.
public record CurrencyTotals(String currency, BigDecimal debits, BigDecimal credits) {

    public boolean balanced() {
        return debits.compareTo(credits) == 0;
    }
}
