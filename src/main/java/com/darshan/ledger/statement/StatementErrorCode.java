package com.darshan.ledger.statement;

import com.darshan.ledger.common.error.ErrorCode;

public enum StatementErrorCode implements ErrorCode {
    INVALID_PERIOD,
    PERIOD_TOO_LONG,
    INVALID_CURSOR;

    @Override
    public String code() {
        return name();
    }
}
