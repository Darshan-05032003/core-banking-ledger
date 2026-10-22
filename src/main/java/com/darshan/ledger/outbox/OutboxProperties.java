package com.darshan.ledger.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @NotNull Duration relayDelay,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration publishTimeout,
        @Min(1) int topicPartitions,
        @Min(1) short topicReplicationFactor) {

    @AssertTrue(message = "relay delay must be a positive duration")
    public boolean isRelayDelayPositive() {
        return relayDelay == null || relayDelay.isPositive();
    }

    @AssertTrue(message = "publish timeout must be a positive duration")
    public boolean isPublishTimeoutPositive() {
        return publishTimeout == null || publishTimeout.isPositive();
    }
}
