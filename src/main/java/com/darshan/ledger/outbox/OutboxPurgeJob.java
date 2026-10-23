package com.darshan.ledger.outbox;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Published outbox rows have done their job once Kafka has them; they are kept for the retention
// period as a replay source and then removed, so the table does not grow with every posting for
// ever. Unpublished rows are never touched, however old: they are events still owed to the topic.
@Component
public class OutboxPurgeJob {

    static final String LOCK_NAME = "purgeOutbox";
    static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPurgeJob(OutboxEventRepository outboxEventRepository, OutboxProperties properties, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.outbox.purge-cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    public void run() {
        purgePublishedBefore(clock.instant().minus(properties.retention()));
    }

    // Each slice is its own short statement in its own transaction, so the purge never blocks
    // the relay for long and a crash halfway simply leaves the rest for the next night.
    public int purgePublishedBefore(Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = outboxEventRepository.deletePublishedBefore(cutoff, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Purged {} outbox events published before {}", total, cutoff);
        }
        return total;
    }
}
