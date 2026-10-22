package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.common.pii.PiiMasker;
import com.darshan.ledger.ledger.AccountBalance;
import java.util.Objects;

// Everything the rules look at. The accounts are null when the request named a number that does
// not exist, which is what the first rule checks; the sender's balance was read under a row lock,
// so the later rules decide on a figure that cannot change until the transaction ends.
public record TransferContext(
        TransferRequest request,
        Money amount,
        Account sender,
        Account beneficiary,
        AccountBalance senderBalance) {

    public TransferContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }

    public boolean hasSender() {
        return sender != null;
    }

    public boolean hasBeneficiary() {
        return beneficiary != null;
    }

    public String maskedFromAccount() {
        return PiiMasker.maskAccountNumber(request.fromAccount());
    }

    public String maskedToAccount() {
        return PiiMasker.maskAccountNumber(request.toAccount());
    }
}
