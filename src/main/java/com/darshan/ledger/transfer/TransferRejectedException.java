package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.common.error.LedgerException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

// A transfer that was refused, by a rule or by the posting itself. Once the attempt has been
// recorded as a FAILED transfer its reference travels with the exception into the problem body.
public final class TransferRejectedException extends LedgerException {

    private final transient TransferAttempt attempt;
    private final Long transferId;
    private final String transferReference;

    private TransferRejectedException(HttpStatus status, ErrorCode errorCode, String message,
            TransferAttempt attempt, Long transferId, String transferReference) {
        super(status, errorCode, message);
        this.attempt = attempt;
        this.transferId = transferId;
        this.transferReference = transferReference;
    }

    static TransferRejectedException of(ErrorCode errorCode, String message) {
        return new TransferRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, null, null, null);
    }

    static TransferRejectedException byRule(TransferRuleResult.Rejected rejected, TransferAttempt attempt) {
        return new TransferRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, rejected.errorCode(),
                rejected.message(), attempt, null, null);
    }

    static TransferRejectedException wrap(LedgerException cause, TransferAttempt attempt) {
        TransferRejectedException rejection = new TransferRejectedException(cause.getStatus(), cause.getErrorCode(),
                cause.getMessage(), attempt, null, null);
        rejection.initCause(cause);
        return rejection;
    }

    TransferRejectedException recordedAs(Transfer transfer) {
        return new TransferRejectedException(getStatus(), getErrorCode(), getMessage(), attempt, transfer.getId(),
                transfer.getReference());
    }

    public Optional<TransferAttempt> attempt() {
        return Optional.ofNullable(attempt);
    }

    public Optional<String> transferReference() {
        return Optional.ofNullable(transferReference);
    }

    @Override
    public Map<String, Object> extensions() {
        if (transferId == null) {
            return Map.of();
        }
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("transferId", transferId);
        extensions.put("transferReference", transferReference);
        return extensions;
    }
}
