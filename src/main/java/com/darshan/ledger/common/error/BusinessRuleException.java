package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends LedgerException {

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }
}
