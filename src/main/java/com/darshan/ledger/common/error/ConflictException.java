package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends LedgerException {

    public ConflictException(String message) {
        this(CommonErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}
