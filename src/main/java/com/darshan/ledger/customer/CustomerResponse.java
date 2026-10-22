package com.darshan.ledger.customer;

import java.time.Instant;
import java.time.LocalDate;

public record CustomerResponse(
        Long id,
        String fullName,
        String maskedEmail,
        String maskedPhone,
        LocalDate dateOfBirth,
        KycStatus kycStatus,
        String kycReason,
        Instant kycDecidedAt,
        Instant createdAt) {
}
