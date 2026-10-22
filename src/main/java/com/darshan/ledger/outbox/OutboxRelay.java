package com.darshan.ledger.outbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Moves outbox rows to Kafka. One batch is one database transaction: the rows are locked
// (SKIP LOCKED, so a second instance works on other rows instead of waiting), each one is sent
// and the broker's acknowledgement awaited, and published_at is written before the commit. A
// crash between the acknowledgement and the commit republishes the row on the next run, which
// is why this is at-least-once delivery and why the consumer must be idempotent. A row that
// cannot be published keeps its attempt count and last error and does not stop the others, but
// it does hold back later rows with the same key, so the per-account order on the topic is
// never broken by a retry.
@Component
public class OutboxRelay {

    static final String LOCK_NAME = "relayOutbox";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager, OutboxProperties properties, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    // Keeps going while batches come back full; a batch with failures is never full, so a broken
    // broker ends the run and the schedule tries again after the delay instead of spinning.
    @Scheduled(fixedDelayString = "${app.outbox.relay-delay}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
    public void run() {
        int published;
        do {
            published = relayOnce();
        } while (published == properties.batchSize());
    }

    public int relayOnce() {
        Integer published = transaction.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextUnpublished(properties.batchSize());
        Set<String> heldBackKeys = new HashSet<>();
        int published = 0;
        for (OutboxEvent event : batch) {
            if (heldBackKeys.contains(event.partitionKey())) {
                continue;
            }
            switch (publish(event)) {
                case PublishOutcome.Published() -> {
                    outboxEventRepository.markPublished(event.id(), clock.instant());
                    published++;
                }
                case PublishOutcome.Failed(String reason) -> {
                    heldBackKeys.add(event.partitionKey());
                    outboxEventRepository.recordFailure(event.id(), reason);
                    log.warn("Outbox event {} not published (attempt {}): {}", event.id(), event.attempts() + 1,
                            reason);
                }
            }
        }
        if (published > 0) {
            log.info("Published {} outbox events", published);
        }
        return published;
    }

    private PublishOutcome publish(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(LedgerTopics.TRANSACTIONS, event.partitionKey(), event.payload());
        record.headers()
                .add(LedgerTopics.EVENT_ID_HEADER, event.id().toString().getBytes(UTF_8))
                .add(LedgerTopics.EVENT_TYPE_HEADER, event.eventType().getBytes(UTF_8));
        try {
            kafkaTemplate.send(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new PublishOutcome.Published();
        } catch (ExecutionException ex) {
            return PublishOutcome.failed(ex.getCause() == null ? ex : ex.getCause());
        } catch (TimeoutException ex) {
            return new PublishOutcome.Failed("No acknowledgement within " + properties.publishTimeout());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new PublishOutcome.Failed("Interrupted while waiting for the acknowledgement");
        } catch (RuntimeException ex) {
            return PublishOutcome.failed(ex);
        }
    }

    private sealed interface PublishOutcome {

        record Published() implements PublishOutcome {
        }

        record Failed(String reason) implements PublishOutcome {
        }

        static PublishOutcome failed(Throwable cause) {
            String message = cause.getMessage() == null ? "" : ": " + cause.getMessage();
            return new Failed(cause.getClass().getSimpleName() + message);
        }
    }
}
