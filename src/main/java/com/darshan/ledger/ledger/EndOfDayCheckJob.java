package com.darshan.ledger.ledger;

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

// Closes the previous business day: once the night's accrual has run, one instance reconciles
// the books and leaves a summary audit row that says whether they were sound. The findings
// themselves are written by the check, whoever triggers it.
@Component
public class EndOfDayCheckJob {

    static final String LOCK_NAME = "checkEndOfDay";
    static final String AUDIT_ACTION = "EOD_CHECK";

    private static final Logger log = LoggerFactory.getLogger(EndOfDayCheckJob.class);

    private final EndOfDayCheck check;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public EndOfDayCheckJob(EndOfDayCheck check, AuditLogWriter auditLogWriter, Clock clock) {
        this.check = check;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.ledger.eod-check-cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1H")
    public void run() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            EndOfDayCheckResult result = check.run(yesterday);
            auditLogWriter.write(new AuditEvent(CurrentUser.SYSTEM, List.of(), AUDIT_ACTION, "BUSINESS_DATE",
                    yesterday.toString(), result.passed() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    result.passed() ? null : LedgerErrorCode.LEDGER_OUT_OF_BALANCE.code(), null, null,
                    details(result)));
        } catch (RuntimeException ex) {
            log.error("End-of-day check for {} could not run", yesterday, ex);
            auditLogWriter.write(new AuditEvent(CurrentUser.SYSTEM, List.of(), AUDIT_ACTION, "BUSINESS_DATE",
                    yesterday.toString(), AuditOutcome.FAILURE, ErrorCodes.of(ex).code(), null, null, Map.of()));
        }
    }

    static Map<String, Object> details(EndOfDayCheckResult result) {
        return Map.of(
                "accountsChecked", result.accountsChecked(),
                "balanceMismatches", result.balanceMismatches(),
                "journalsChecked", result.journalsChecked(),
                "unbalancedJournals", result.unbalancedJournals(),
                "totalsBalanced", result.totalsBalanced());
    }
}
