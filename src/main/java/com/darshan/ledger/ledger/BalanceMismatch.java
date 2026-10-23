package com.darshan.ledger.ledger;

import java.math.BigDecimal;

// An account whose balance row disagrees with its own history: expected is what the ledger
// lines and the active holds say, actual is what the row says.
public record BalanceMismatch(
        long accountId,
        String maskedAccountNumber,
        String currency,
        BigDecimal expectedLedgerBalance,
        BigDecimal actualLedgerBalance,
        BigDecimal expectedAvailableBalance,
        BigDecimal actualAvailableBalance) {
}
