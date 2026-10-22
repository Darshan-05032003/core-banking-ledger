package com.darshan.ledger.notification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// retryAttempts counts deliveries in total, including the first one.
@Validated
@ConfigurationProperties("app.notification")
public record NotificationProperties(
        @NotBlank String groupId,
        @Min(1) int retryAttempts,
        @NotNull Duration retryBackoff) {

    @AssertTrue(message = "retry backoff must not be negative")
    public boolean isRetryBackoffNotNegative() {
        return retryBackoff == null || !retryBackoff.isNegative();
    }
}
