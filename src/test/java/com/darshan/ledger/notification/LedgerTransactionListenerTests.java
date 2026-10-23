package com.darshan.ledger.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.darshan.ledger.common.web.CorrelationIdFilter;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.outbox.LedgerTopics;
import com.darshan.ledger.outbox.LedgerTransactionPostedV1;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LedgerTransactionListenerTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");

    private final NotificationService notificationService = mock(NotificationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LedgerTransactionListener listener =
            new LedgerTransactionListener(notificationService, new NotificationMetrics(registry), objectMapper);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aRecordWithoutAnEventIdIsUnsupportedAndNeverReachesTheService() throws Exception {
        ObjectNode withoutEventId = (ObjectNode) objectMapper.readTree(
                objectMapper.writeValueAsString(event(UUID.randomUUID())));
        withoutEventId.remove("eventId");

        assertThatThrownBy(() -> listener.onMessage(record(objectMapper.writeValueAsString(withoutEventId), "no-id")))
                .isInstanceOf(UnsupportedEventException.class)
                .hasMessageContaining("carries no event id");
        assertThatThrownBy(() -> listener.onMessage(record("null", "null-body")))
                .isInstanceOf(UnsupportedEventException.class)
                .hasMessageContaining("carries no event id");
        verifyNoInteractions(notificationService);
    }

    @Test
    void theEventIdHeaderIsTheCorrelationIdWhileTheRecordIsProcessedAndIsClearedAfterwards() throws Exception {
        LedgerTransactionPostedV1 event = event(UUID.randomUUID());
        AtomicReference<String> correlationIdSeen = new AtomicReference<>();
        given(notificationService.notify(any())).willAnswer(invocation -> {
            correlationIdSeen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return Optional.of(new Notification(event.eventId(), 157L, NotificationChannel.SMS, "XXXXXXX3210",
                    "INR 250.50 debited", NOW));
        });

        listener.onMessage(record(objectMapper.writeValueAsString(event), event.eventId().toString()));

        assertThat(correlationIdSeen.get()).isEqualTo(event.eventId().toString());
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(registry.get(NotificationMetrics.SENT).tag(NotificationMetrics.CHANNEL_TAG, "SMS").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void withoutAHeaderTheRecordCoordinatesIdentifyTheMessageInTheLogs() throws Exception {
        LedgerTransactionPostedV1 event = event(UUID.randomUUID());
        AtomicReference<String> correlationIdSeen = new AtomicReference<>();
        given(notificationService.notify(any())).willAnswer(invocation -> {
            correlationIdSeen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return Optional.empty();
        });

        listener.onMessage(new ConsumerRecord<>(LedgerTopics.TRANSACTIONS, 2, 41L, "157",
                objectMapper.writeValueAsString(event)));

        assertThat(correlationIdSeen.get()).isEqualTo(LedgerTopics.TRANSACTIONS + "-2-41");
        assertThat(registry.get(NotificationMetrics.SENT).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private static ConsumerRecord<String, String> record(String value, String eventIdHeader) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(LedgerTopics.TRANSACTIONS, 0, 7L, "157", value);
        record.headers().add(LedgerTopics.EVENT_ID_HEADER, eventIdHeader.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static LedgerTransactionPostedV1 event(UUID eventId) {
        BigDecimal amount = new BigDecimal("250.50");
        return new LedgerTransactionPostedV1(LedgerTransactionPostedV1.SCHEMA_VERSION, eventId, 917L, "JRN-0000000917",
                JournalType.TRANSFER, NOW, null, 157L, "XXXXXXXX0157", EntryDirection.DEBIT, amount, "INR",
                List.of(new LedgerTransactionPostedV1.Leg(157L, "XXXXXXXX0157", null, EntryDirection.DEBIT, amount),
                        new LedgerTransactionPostedV1.Leg(165L, "XXXXXXXX0165", null, EntryDirection.CREDIT, amount)));
    }
}
