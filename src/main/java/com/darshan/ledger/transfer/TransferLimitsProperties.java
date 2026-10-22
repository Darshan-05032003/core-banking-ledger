package com.darshan.ledger.transfer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.limits")
public record TransferLimitsProperties(
        @NotNull @DecimalMin("0.01") BigDecimal perTransaction,
        @NotNull @DecimalMin("0.01") BigDecimal daily) {

    @AssertTrue(message = "daily limit must be at least the per-transaction limit")
    public boolean isDailyAtLeastPerTransaction() {
        return perTransaction == null || daily == null || daily.compareTo(perTransaction) >= 0;
    }
}
