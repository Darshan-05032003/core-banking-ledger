package com.darshan.ledger.ledger;

import java.time.Instant;
import java.util.List;

public record JournalResponse(
        Long id,
        String reference,
        JournalType type,
        String description,
        Instant postedAt,
        String createdBy,
        Long reversalOfId,
        List<LedgerEntryResponse> entries) {
}
