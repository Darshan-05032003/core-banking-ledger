package com.darshan.ledger.common.error;

public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    LOCK_TIMEOUT,
    ACCESS_DENIED,
    RATE_LIMIT_EXCEEDED,
    INTERNAL_ERROR;

    @Override
    public String code() {
        return name();
    }
}
