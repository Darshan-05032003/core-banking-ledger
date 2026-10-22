package com.darshan.ledger.approval;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.approval")
public record ApprovalProperties(
        @NotNull @DecimalMin("0.01") BigDecimal threshold,
        @NotNull Duration pendingExpiry) {

    @AssertTrue(message = "pending expiry must be a positive duration")
    public boolean isPendingExpiryPositive() {
        return pendingExpiry == null || pendingExpiry.isPositive();
    }
}
