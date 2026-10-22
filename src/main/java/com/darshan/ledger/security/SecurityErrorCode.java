package com.darshan.ledger.security;

import com.darshan.ledger.common.error.ErrorCode;

public enum SecurityErrorCode implements ErrorCode {
    AUTHENTICATION_REQUIRED,
    TOKEN_EXPIRED,
    INVALID_TOKEN;

    @Override
    public String code() {
        return name();
    }
}
