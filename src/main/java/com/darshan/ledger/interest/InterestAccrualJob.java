package com.darshan.ledger.interest;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.common.error.ErrorCodes;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// The nightly entry point: shortly after midnight it accrues the day that has just ended, on
// whichever instance wins the ShedLock row, and leaves one audit row for the run with "system"
// as the actor. Runs triggered by an administrator go through the controller instead, where the
// audit aspect records who asked.
@Component
public class InterestAccrualJob {

    static final String LOCK_NAME = "accrueInterest";
    static final String AUDIT_ACTION = "INTEREST_ACCRUAL";

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualJob.class);

    private final InterestAccrualService service;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public InterestAccrualJob(InterestAccrualService service, AuditLogWriter auditLogWriter, Clock clock) {
        this.service = service;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.interest.accrual-cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1H")
    public void run() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            InterestAccrualResult result = service.accrue(yesterday);
            auditLogWriter.write(new AuditEvent(CurrentUser.SYSTEM, List.of(), AUDIT_ACTION, "ACCRUAL_DATE",
                    yesterday.toString(), AuditOutcome.SUCCESS, null, null, null, details(result)));
        } catch (RuntimeException ex) {
            log.error("Interest accrual for {} failed", yesterday, ex);
            auditLogWriter.write(new AuditEvent(CurrentUser.SYSTEM, List.of(), AUDIT_ACTION, "ACCRUAL_DATE",
                    yesterday.toString(), AuditOutcome.FAILURE, ErrorCodes.of(ex).code(), null, null, Map.of()));
        }
    }

    static Map<String, Object> details(InterestAccrualResult result) {
        return Map.of(
                "accountsProcessed", result.accountsProcessed(),
                "accrued", result.accrued(),
                "alreadyAccrued", result.alreadyAccrued(),
                "monthEnd", result.monthEnd(),
                "posted", result.posted(),
                "carriedForward", result.carriedForward(),
                "postingFailures", result.postingFailures());
    }
}
