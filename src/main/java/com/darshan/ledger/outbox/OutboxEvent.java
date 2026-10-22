package com.darshan.ledger.outbox;

import java.util.UUID;

// What the relay needs of an unpublished row: the Kafka key, the bytes to send and the headers.
public record OutboxEvent(UUID id, String partitionKey, String eventType, int eventVersion, String payload,
        int attempts) {
}
