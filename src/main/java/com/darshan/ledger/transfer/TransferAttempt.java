package com.darshan.ledger.transfer;

import com.darshan.ledger.common.money.Money;
import java.util.Objects;

// The two resolved parties of a rejected transfer, kept so the failure can be recorded against
// real accounts after the transaction that rejected it has rolled back.
public record TransferAttempt(Long fromAccountId, Long toAccountId, Money amount, String remarks) {

    public TransferAttempt {
        Objects.requireNonNull(fromAccountId, "from account id must not be null");
        Objects.requireNonNull(toAccountId, "to account id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
