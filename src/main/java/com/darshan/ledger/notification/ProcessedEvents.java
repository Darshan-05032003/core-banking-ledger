package com.darshan.ledger.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// The idempotency record of the consumer. ON CONFLICT DO NOTHING makes the duplicate case a
// normal "zero rows inserted" instead of a constraint error that would abort the transaction.
@Repository
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markProcessed(UUID eventId, String consumerGroup, Instant processedAt) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_event (event_id, consumer_group, processed_at)
                VALUES (:eventId, :consumerGroup, :processedAt)
                ON CONFLICT DO NOTHING
                """)
                .param("eventId", eventId)
                .param("consumerGroup", consumerGroup)
                .param("processedAt", processedAt.atOffset(ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }
}
