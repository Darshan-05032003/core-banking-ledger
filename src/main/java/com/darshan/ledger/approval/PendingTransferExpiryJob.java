package com.darshan.ledger.approval;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.ledger.HoldRepository;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Sweeps pending transfers whose hold has lapsed. Nothing here is transactional: each transfer
// is expired in its own transaction by the expirer, and its audit row is written after that
// transaction has ended, the same way the audit aspect works for requests. There is no HTTP
// actor, so the rows name "system". Running the sweep twice is harmless: the second run finds
// nothing due, which is what lets ShedLock hand the schedule to whichever instance wins the lock.
@Component
public class PendingTransferExpiryJob {

    static final String LOCK_NAME = "expirePendingTransfers";
    static final String AUDIT_ACTION = "TRANSFER_EXPIRE";

    private static final Logger log = LoggerFactory.getLogger(PendingTransferExpiryJob.class);
    private static final int BATCH_SIZE = 100;

    private final HoldRepository holdRepository;
    private final PendingTransferExpirer expirer;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public PendingTransferExpiryJob(HoldRepository holdRepository, PendingTransferExpirer expirer,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.holdRepository = holdRepository;
        this.expirer = expirer;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.approval.expiry-cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void run() {
        expireDue(clock.instant());
    }

    // Batches keep memory flat however long the queue is. A batch that expired nothing (every
    // remaining transfer failed to lock) ends the sweep rather than spinning on the same rows.
    public int expireDue(Instant now) {
        int expired = 0;
        List<Long> batch;
        do {
            batch = holdRepository.findTransferIdsOfHoldsExpiredAt(now, PageRequest.of(0, BATCH_SIZE));
            int before = expired;
            for (Long transferId : batch) {
                expired += expireOne(transferId, now) ? 1 : 0;
            }
            if (expired == before) {
                break;
            }
        } while (batch.size() == BATCH_SIZE);
        if (expired > 0) {
            log.info("Expired {} pending transfers", expired);
        }
        return expired;
    }

    private boolean expireOne(Long transferId, Instant now) {
        try {
            return expirer.expire(transferId, now)
                    .map(this::audit)
                    .orElse(false);
        } catch (RuntimeException ex) {
            log.warn("Pending transfer {} could not be expired now: {}", transferId, ex.getMessage());
            return false;
        }
    }

    private boolean audit(ExpiredTransfer expired) {
        auditLogWriter.write(new AuditEvent(CurrentUser.SYSTEM, List.of(), AUDIT_ACTION, "TRANSFER",
                String.valueOf(expired.transferId()), AuditOutcome.SUCCESS, null, null, null,
                Map.of("transferReference", expired.reference(), "holdId", expired.holdId(),
                        "amount", expired.amount().amount(), "currency", expired.amount().currency().getCurrencyCode())));
        return true;
    }
}
