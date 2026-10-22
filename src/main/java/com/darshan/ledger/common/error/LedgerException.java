package com.darshan.ledger.common.error;

import java.util.Map;
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

    // Extra machine-readable members for the problem body (RFC 9457 extension members), for
    // example the reference of the transfer attempt a rejection created.
    public Map<String, Object> extensions() {
        return Map.of();
    }
}
