package com.darshan.ledger.ledger;

import com.darshan.ledger.audit.Audited;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Runs the reconciliation on demand, for instance after an incident or before a release. The
// date names the business day the report is filed under; the figures are always the ledger as it
// stands now, because the balance rows have no history to check an earlier day against.
@RestController
public class EndOfDayCheckController {

    private final EndOfDayCheck check;

    public EndOfDayCheckController(EndOfDayCheck check) {
        this.check = check;
    }

    @PostMapping("/api/v1/admin/jobs/eod-check")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "EOD_CHECK_RUN", targetType = "BUSINESS_DATE",
            targetId = "#date != null ? #date : #result?.businessDate()",
            details = "{accountsChecked: #result?.accountsChecked(), balanceMismatches: #result?.balanceMismatches(), "
                    + "journalsChecked: #result?.journalsChecked(), unbalancedJournals: #result?.unbalancedJournals(), "
                    + "passed: #result?.passed()}")
    public EndOfDayCheckResult run(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return date == null ? check.run() : check.run(date);
    }
}
