package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

public abstract class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode errorCode;

    protected LedgerException(HttpStatus status, ErrorCode errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
