package com.darshan.ledger.ledger;

import java.math.BigDecimal;

public record LedgerEntryResponse(
        Long accountId,
        String maskedAccountNumber,
        GlAccountCode glAccount,
        EntryDirection direction,
        BigDecimal amount,
        String currency) {
}
