package com.darshan.ledger.account;

import java.math.BigDecimal;

public record AccountBalanceResponse(
        Long accountId,
        String maskedAccountNumber,
        BigDecimal ledgerBalance,
        BigDecimal availableBalance,
        String currency) {
}
