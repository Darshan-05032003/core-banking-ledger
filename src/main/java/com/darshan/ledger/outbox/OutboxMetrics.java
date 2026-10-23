package com.darshan.ledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

// Two gauges that say whether events are reaching Kafka: how many rows are waiting and how old
// the oldest one is. Both are read from the table when the registry is scraped, so they are
// right even if the relay is dead, which is the case they exist for. An alert on the lag is the
// operator's view of the at-least-once promise: a relay that cannot publish shows up here within
// a scrape interval, not when a customer asks why no SMS arrived.
@Component
public class OutboxMetrics {

    public static final String LAG = "outbox.lag";
    public static final String UNPUBLISHED = "outbox.unpublished";

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Gauge.builder(LAG, this, OutboxMetrics::lagSeconds)
                .description("Age of the oldest outbox event not yet acknowledged by Kafka, 0 when none is waiting")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
        Gauge.builder(UNPUBLISHED, this, OutboxMetrics::unpublished)
                .description("Outbox events not yet acknowledged by Kafka")
                .strongReference(true)
                .register(registry);
    }

    double lagSeconds() {
        return repository.oldestUnpublishedCreatedAt()
                .map(createdAt -> Math.max(0, Duration.between(createdAt, clock.instant()).toMillis()) / 1000.0)
                .orElse(0.0);
    }

    double unpublished() {
        return repository.countUnpublished();
    }
}
