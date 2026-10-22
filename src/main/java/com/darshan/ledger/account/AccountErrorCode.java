package com.darshan.ledger.account;

import com.darshan.ledger.common.error.ErrorCode;

public enum AccountErrorCode implements ErrorCode {
    CUSTOMER_NOT_VERIFIED,
    UNSUPPORTED_CURRENCY,
    ACCOUNT_NOT_ACTIVE,
    ACCOUNT_NOT_FROZEN,
    ACCOUNT_CLOSED,
    FUNDS_ON_HOLD,
    BALANCE_NOT_ZERO;

    @Override
    public String code() {
        return name();
    }
}
