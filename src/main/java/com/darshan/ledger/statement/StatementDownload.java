package com.darshan.ledger.statement;

import java.util.Currency;

// Everything a CSV download needs, resolved before the first byte is sent: once streaming has
// started there is no way left to answer with a 404 or a 400.
public record StatementDownload(Long accountId, String maskedAccountNumber, Currency currency, StatementPeriod period) {

    public String fileName() {
        return "statement-" + maskedAccountNumber + "-" + period.from() + "-" + period.to() + ".csv";
    }
}
