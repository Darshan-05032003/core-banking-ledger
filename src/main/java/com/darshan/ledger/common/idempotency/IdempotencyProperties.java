package com.darshan.ledger.common.idempotency;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.idempotency")
public record IdempotencyProperties(@NotNull Duration inProgressTimeout) {

    @AssertTrue(message = "in-progress timeout must be a positive duration")
    public boolean isInProgressTimeoutPositive() {
        return inProgressTimeout == null || inProgressTimeout.isPositive();
    }
}
