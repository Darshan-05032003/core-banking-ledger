package com.darshan.ledger.account;

import java.time.LocalDate;

public record AccountResponse(
        Long id,
        String maskedAccountNumber,
        Long customerId,
        AccountType type,
        String currency,
        AccountStatus status,
        LocalDate openedOn,
        LocalDate closedOn) {
}
