package com.darshan.ledger.outbox;

import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The published contract for a posted journal, addressed to one customer account: the account
// this copy is about, how it was affected, and the whole journal for context. One event exists
// per customer account a journal touched, so a consumer reading one account's partition sees
// everything that happened to that account in posting order. Account numbers travel masked.
// The schema version is part of the payload so a consumer can refuse what it does not understand.
public record LedgerTransactionPostedV1(
        int schemaVersion,
        UUID eventId,
        long journalId,
        String journalReference,
        JournalType journalType,
        Instant postedAt,
        Long reversalOfJournalId,
        long accountId,
        String maskedAccountNumber,
        EntryDirection direction,
        BigDecimal amount,
        String currency,
        List<Leg> legs) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "LedgerTransactionPosted";

    public LedgerTransactionPostedV1 {
        legs = List.copyOf(legs);
    }

    public boolean isSupportedVersion() {
        return schemaVersion == SCHEMA_VERSION;
    }

    // Exactly one of accountId and glAccount is set, mirroring a ledger line.
    public record Leg(Long accountId, String maskedAccountNumber, String glAccount, EntryDirection direction,
            BigDecimal amount) {
    }
}
