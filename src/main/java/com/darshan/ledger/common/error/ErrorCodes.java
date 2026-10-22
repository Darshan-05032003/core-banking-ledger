package com.darshan.ledger.common.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

// The machine-readable code a failure is reported under, mirroring what the exception handler
// returns to the client, for places that record failures without rendering a response.
public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static ErrorCode of(Throwable failure) {
        return switch (failure) {
            case LedgerException ex -> ex.getErrorCode();
            case AccessDeniedException ex -> CommonErrorCode.ACCESS_DENIED;
            case PessimisticLockingFailureException ex -> CommonErrorCode.LOCK_TIMEOUT;
            case OptimisticLockingFailureException ex -> CommonErrorCode.CONFLICT;
            case DataIntegrityViolationException ex -> CommonErrorCode.CONFLICT;
            default -> CommonErrorCode.INTERNAL_ERROR;
        };
    }
}
