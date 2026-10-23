package com.darshan.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// The ways a send can go wrong that the Kafka container cannot be made to produce on demand: a
// broker that never answers, a producer that throws, a thread that is interrupted while waiting.
// Each must leave the row unpublished with a reason, never lose it, and never stop the relay.
@ExtendWith(MockitoExtension.class)
class OutboxRelayTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final OutboxProperties properties = new OutboxProperties(Duration.ofSeconds(1), 100,
            Duration.ofMillis(200), 3, (short) 1, Duration.ofDays(7), "0 0 2 * * *");

    @Test
    void anAcknowledgedSendMarksTheRowPublishedAtTheApplicationClock() {
        OutboxEvent event = event();
        given(repository.lockNextUnpublished(100)).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));

        assertThat(relay().relayOnce()).isEqualTo(1);

        verify(repository).markPublished(event.id(), NOW);
        verify(repository, never()).recordFailure(any(), any());
    }

    @Test
    void aBrokerThatNeverAnswersIsRecordedAsATimeoutAfterThePublishTimeout() {
        OutboxEvent event = event();
        given(repository.lockNextUnpublished(100)).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());

        assertThat(relay().relayOnce()).isZero();

        verify(repository).recordFailure(event.id(), "No acknowledgement within PT0.2S");
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    void aProducerThatThrowsIsRecordedWithTheExceptionTypeAndMessage() {
        OutboxEvent event = event();
        given(repository.lockNextUnpublished(100)).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willThrow(new KafkaException("Producer is closed"));

        assertThat(relay().relayOnce()).isZero();

        verify(repository).recordFailure(event.id(), "KafkaException: Producer is closed");
    }

    @Test
    void anInterruptedWaitIsRecordedAndTheInterruptFlagIsKept() {
        OutboxEvent event = event();
        given(repository.lockNextUnpublished(100)).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThat(relay().relayOnce()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).as("the flag is restored for the caller").isTrue();
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }

        verify(repository).recordFailure(event.id(), "Interrupted while waiting for the acknowledgement");
    }

    private OutboxRelay relay() {
        return new OutboxRelay(repository, kafkaTemplate, transactionManager, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "157", "LedgerTransactionPosted", 1, "{\"schemaVersion\":1}", 0);
    }
}
