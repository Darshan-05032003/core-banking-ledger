package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends LedgerException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, CommonErrorCode.CONFLICT, message);
    }
}
