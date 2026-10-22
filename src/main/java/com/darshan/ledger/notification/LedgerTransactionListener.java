package com.darshan.ledger.notification;

import com.darshan.ledger.common.web.CorrelationIdFilter;
import com.darshan.ledger.outbox.LedgerTopics;
import com.darshan.ledger.outbox.LedgerTransactionPostedV1;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Consumes the raw JSON and parses it here rather than in a deserializer, so a record that is
// not JSON, carries a schema version this code does not know, or fails in any other permanent
// way is one kind of failure: UnsupportedEventException, which the error handler sends to the
// dead-letter topic without retrying. Anything else that goes wrong (the database is briefly
// unavailable) is retried with a backoff before it is dead-lettered.
@Component
public class LedgerTransactionListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public LedgerTransactionListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = LedgerTopics.TRANSACTIONS, groupId = "${app.notification.group-id}")
    public void onMessage(ConsumerRecord<String, String> record) {
        MDC.put(CorrelationIdFilter.MDC_KEY, eventIdHeader(record));
        try {
            notificationService.notify(parse(record.value()));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private LedgerTransactionPostedV1 parse(String json) {
        LedgerTransactionPostedV1 event;
        try {
            event = objectMapper.readValue(json, LedgerTransactionPostedV1.class);
        } catch (JacksonException ex) {
            throw new UnsupportedEventException("Record is not a readable " + LedgerTransactionPostedV1.EVENT_TYPE
                    + " event: " + ex.getOriginalMessage(), ex);
        }
        if (event == null || event.eventId() == null) {
            throw new UnsupportedEventException("Record carries no event id");
        }
        if (!event.isSupportedVersion()) {
            throw new UnsupportedEventException("Event " + event.eventId() + " has schema version "
                    + event.schemaVersion() + "; this consumer understands version "
                    + LedgerTransactionPostedV1.SCHEMA_VERSION);
        }
        return event;
    }

    private static String eventIdHeader(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(LedgerTopics.EVENT_ID_HEADER);
        return header == null ? record.topic() + "-" + record.partition() + "-" + record.offset()
                : new String(header.value(), StandardCharsets.UTF_8);
    }
}
