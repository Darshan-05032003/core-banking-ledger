package com.darshan.ledger.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Plain SQL on purpose: the outbox is a queue table, and the one query that matters, "take the
// next unpublished rows and skip the ones another relay holds", is a locking clause JPQL does not
// have. Nothing here is an aggregate anyone edits; rows are inserted, stamped, and left behind.
@Repository
public class OutboxEventRepository {

    private static final String NEXT_UNPUBLISHED_SQL = """
            SELECT id, partition_key, event_type, event_version, payload::text AS payload, attempts
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at, seq_no
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbc;

    public OutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String aggregateType, String aggregateId, String eventType, int eventVersion,
            String partitionKey, String payload, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, event_version,
                                          partition_key, payload, created_at)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, :eventVersion,
                        :partitionKey, CAST(:payload AS jsonb), :createdAt)
                """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("eventVersion", eventVersion)
                .param("partitionKey", partitionKey)
                .param("payload", payload)
                .param("createdAt", createdAt.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    // Rows another relay transaction already holds are skipped rather than waited for, so two
    // instances that both wake up share the backlog instead of queueing behind each other. The
    // locks last until the caller's transaction ends, which is also when published_at is written.
    public List<OutboxEvent> lockNextUnpublished(int limit) {
        return jdbc.sql(NEXT_UNPUBLISHED_SQL)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("partition_key"),
                        rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
    }

    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("UPDATE outbox_event SET published_at = :publishedAt WHERE id = :id")
                .param("publishedAt", publishedAt.atOffset(java.time.ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    public void recordFailure(UUID id, String error) {
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", error)
                .param("id", id)
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }
}
