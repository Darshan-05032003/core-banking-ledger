package com.darshan.ledger.outbox;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.ledger.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// Fixture rows are inserted straight into the table with chosen timestamps and removed again at
// the end, so the relay of other tests never sees them.
@IntegrationTest
class OutboxPurgeJobTests {

    private static final String FIXTURE_TYPE = "PurgeFixture";

    @Autowired
    private OutboxPurgeJob job;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeFixtures() {
        jdbc.update("delete from outbox_event where event_type = ?", FIXTURE_TYPE);
    }

    @Test
    void onlyPublishedRowsOlderThanTheRetentionAreRemoved() {
        Instant cutoff = NOW.minus(properties.retention());
        UUID oldPublished = fixture(NOW.minus(Duration.ofDays(30)), cutoff.minusSeconds(1));
        UUID publishedAtTheCutoff = fixture(NOW.minus(Duration.ofDays(30)), cutoff);
        UUID recentlyPublished = fixture(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofHours(1)));
        UUID oldUnpublished = fixture(NOW.minus(Duration.ofDays(30)), null);

        int purged = job.purgePublishedBefore(cutoff);

        assertThat(purged).isEqualTo(1);
        assertThat(remaining()).doesNotContain(oldPublished)
                .contains(publishedAtTheCutoff, recentlyPublished, oldUnpublished);
    }

    @Test
    void thePurgeWorksThroughTheBacklogInSlices() {
        Instant cutoff = NOW.minus(properties.retention());
        List<UUID> stale = IntStream.range(0, OutboxPurgeJob.BATCH_SIZE + 5)
                .mapToObj(i -> fixture(NOW.minus(Duration.ofDays(40)), cutoff.minus(Duration.ofDays(1))))
                .toList();

        assertThat(job.purgePublishedBefore(cutoff)).isEqualTo(stale.size());
        assertThat(remaining()).doesNotContainAnyElementsOf(stale);
        assertThat(job.purgePublishedBefore(cutoff)).isZero();
    }

    @Test
    void theScheduledRunUsesTheConfiguredRetentionAndTakesTheShedLock() {
        UUID beyondRetention = fixture(NOW.minus(Duration.ofDays(30)),
                NOW.minus(properties.retention()).minus(Duration.ofMinutes(1)));
        UUID withinRetention = fixture(NOW.minus(Duration.ofDays(30)),
                NOW.minus(properties.retention()).plus(Duration.ofMinutes(1)));

        job.run();

        assertThat(remaining()).doesNotContain(beyondRetention).contains(withinRetention);
        String lockedBy = jdbc.queryForObject("select locked_by from shedlock where name = ?", String.class,
                OutboxPurgeJob.LOCK_NAME);
        OffsetDateTime lockUntil = jdbc.queryForObject("select lock_until from shedlock where name = ?",
                OffsetDateTime.class, OutboxPurgeJob.LOCK_NAME);
        assertThat(lockedBy).isNotBlank();
        assertThat(lockUntil).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
    }

    private UUID fixture(Instant createdAt, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        repository.insert(id, "JOURNAL", "0", FIXTURE_TYPE, 1, "0", "{}", createdAt);
        if (publishedAt != null) {
            repository.markPublished(id, publishedAt);
        }
        return id;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("select id from outbox_event where event_type = ?", UUID.class, FIXTURE_TYPE);
    }
}
