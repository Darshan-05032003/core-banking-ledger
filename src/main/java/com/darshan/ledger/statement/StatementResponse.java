package com.darshan.ledger.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatementResponse(
        Long accountId,
        String maskedAccountNumber,
        String currency,
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<StatementLine> lines,
        String nextCursor) {
}
