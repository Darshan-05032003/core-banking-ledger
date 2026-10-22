package com.darshan.ledger.auth;

import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.common.error.LedgerException;
import org.springframework.http.HttpStatus;

public class AuthenticationFailedException extends LedgerException {

    public AuthenticationFailedException(ErrorCode errorCode, String message) {
        super(HttpStatus.UNAUTHORIZED, errorCode, message);
    }
}
