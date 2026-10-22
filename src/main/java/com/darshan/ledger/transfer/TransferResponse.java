package com.darshan.ledger.transfer;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        Long id,
        String reference,
        TransferStatus status,
        Long fromAccountId,
        String maskedFromAccountNumber,
        Long toAccountId,
        String maskedToAccountNumber,
        BigDecimal amount,
        String currency,
        String remarks,
        Long journalId,
        String journalReference,
        String failureCode,
        String failureReason,
        String createdBy,
        Instant createdAt,
        Instant completedAt) {
}
