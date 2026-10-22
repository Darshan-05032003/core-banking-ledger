package com.darshan.ledger.transfer;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;

// Accounts are named by their full twelve-digit number, the way a customer writes them on a
// payee form; the Luhn check digit catches a mistyped number before any lookup.
public record TransferRequest(
        @NotBlank @Pattern(regexp = "[0-9]{12}", message = "must be a 12-digit account number") String fromAccount,
        @NotBlank @Pattern(regexp = "[0-9]{12}", message = "must be a 12-digit account number") String toAccount,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code") String currency,
        @Size(max = 200) String remarks) {

    // Two requests that mean the same transfer must hash the same for idempotency: 100 and 100.00,
    // "Rent" and "Rent " with a trailing space.
    public TransferRequest normalised() {
        String trimmedRemarks = remarks == null || remarks.isBlank() ? null : remarks.trim();
        return new TransferRequest(fromAccount, toAccount, amount.setScale(2, RoundingMode.UNNECESSARY), currency,
                trimmedRemarks);
    }
}
