package com.darshan.ledger.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @NotNull Long customerId,
        @NotNull AccountType type,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code") String currency) {
}
