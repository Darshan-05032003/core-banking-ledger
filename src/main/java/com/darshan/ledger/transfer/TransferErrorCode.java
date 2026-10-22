package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.ErrorCode;

public enum TransferErrorCode implements ErrorCode {
    INVALID_ACCOUNT_NUMBER,
    ACCOUNT_NOT_FOUND,
    SAME_ACCOUNT,
    INVALID_AMOUNT,
    PER_TRANSACTION_LIMIT_EXCEEDED,
    DAILY_LIMIT_EXCEEDED,
    TRANSFER_NOT_PENDING;

    @Override
    public String code() {
        return name();
    }
}
