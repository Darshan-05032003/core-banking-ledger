package com.darshan.ledger.transfer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

// Lives with the transfer rules because the transfer module decides which transfers wait for a
// checker and for how long the reservation stands; the approval module only acts on that decision.
@Validated
@ConfigurationProperties("app.approval")
public record ApprovalProperties(
        @NotNull @DecimalMin("0.01") BigDecimal threshold,
        @NotNull Duration pendingExpiry,
        @NotBlank String expiryCron) {

    @AssertTrue(message = "pending expiry must be a positive duration")
    public boolean isPendingExpiryPositive() {
        return pendingExpiry == null || pendingExpiry.isPositive();
    }

    @AssertTrue(message = "expiry cron must be a valid Spring cron expression")
    public boolean isExpiryCronValid() {
        return expiryCron == null || CronExpression.isValidExpression(expiryCron);
    }
}
