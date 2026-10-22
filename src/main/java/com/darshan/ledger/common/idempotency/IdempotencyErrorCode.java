package com.darshan.ledger.common.idempotency;

import com.darshan.ledger.common.error.ErrorCode;

public enum IdempotencyErrorCode implements ErrorCode {
    IDEMPOTENCY_KEY_REQUIRED,
    IDEMPOTENCY_KEY_INVALID,
    IDEMPOTENCY_KEY_REUSED,
    REQUEST_IN_PROGRESS;

    @Override
    public String code() {
        return name();
    }
}
