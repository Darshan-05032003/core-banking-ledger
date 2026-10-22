package com.darshan.ledger.common.money;

import java.util.Currency;

public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine amounts in " + left.getCurrencyCode() + " and " + right.getCurrencyCode());
    }
}
