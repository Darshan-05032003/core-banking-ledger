package com.darshan.ledger.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @NotNull Duration relayDelay,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration publishTimeout,
        @Min(1) int topicPartitions,
        @Min(1) short topicReplicationFactor,
        @NotNull Duration retention,
        @NotBlank String purgeCron) {

    @AssertTrue(message = "relay delay must be a positive duration")
    public boolean isRelayDelayPositive() {
        return relayDelay == null || relayDelay.isPositive();
    }

    @AssertTrue(message = "publish timeout must be a positive duration")
    public boolean isPublishTimeoutPositive() {
        return publishTimeout == null || publishTimeout.isPositive();
    }

    @AssertTrue(message = "retention must be a positive duration")
    public boolean isRetentionPositive() {
        return retention == null || retention.isPositive();
    }

    @AssertTrue(message = "purge cron must be a valid Spring cron expression")
    public boolean isPurgeCronValid() {
        return purgeCron == null || CronExpression.isValidExpression(purgeCron);
    }
}
