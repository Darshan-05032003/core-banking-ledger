package com.darshan.ledger.ledger;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ledger")
public record LedgerProperties(@NotNull Duration lockTimeout) {

    @AssertTrue(message = "lock timeout must be a positive duration")
    public boolean isLockTimeoutPositive() {
        return lockTimeout == null || lockTimeout.isPositive();
    }
}
