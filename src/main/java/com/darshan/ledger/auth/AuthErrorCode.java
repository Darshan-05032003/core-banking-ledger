package com.darshan.ledger.auth;

import com.darshan.ledger.common.error.ErrorCode;

public enum AuthErrorCode implements ErrorCode {
    INVALID_CREDENTIALS,
    USER_DISABLED,
    USERNAME_TAKEN,
    CUSTOMER_ALREADY_LINKED,
    CUSTOMER_ROLE_REQUIRED,
    USER_STATUS_UNCHANGED;

    @Override
    public String code() {
        return name();
    }
}
