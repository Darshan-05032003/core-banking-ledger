package com.darshan.ledger.ledger;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ledger")
public record LedgerProperties(@NotNull Duration lockTimeout, @NotBlank String eodCheckCron) {

    @AssertTrue(message = "lock timeout must be a positive duration")
    public boolean isLockTimeoutPositive() {
        return lockTimeout == null || lockTimeout.isPositive();
    }

    @AssertTrue(message = "end-of-day check cron must be a valid Spring cron expression")
    public boolean isEodCheckCronValid() {
        return eodCheckCron == null || CronExpression.isValidExpression(eodCheckCron);
    }
}
