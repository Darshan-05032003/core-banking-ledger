package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

// A request that is malformed in a way Bean Validation cannot express, such as a missing header.
public class InvalidRequestException extends LedgerException {

    public InvalidRequestException(ErrorCode errorCode, String message) {
        super(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}
