package com.darshan.ledger.interest;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Lets an administrator accrue (and, on a month-end date, pay out) a specific day: a missed
// schedule, a late run after a recovery, or a check that a re-run changes nothing. The service
// is idempotent, so a day that was already accrued reports it rather than doubling anything.
@RestController
@Tag(name = ApiTags.JOBS)
public class InterestAccrualController {

    private final InterestAccrualService service;

    public InterestAccrualController(InterestAccrualService service) {
        this.service = service;
    }

    @Operation(operationId = "runInterestAccrual", summary = "Accrue savings interest for a day that has ended")
    @PostMapping("/api/v1/admin/jobs/interest-accrual")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "INTEREST_ACCRUAL_RUN", targetType = "ACCRUAL_DATE", targetId = "#date",
            details = "{accountsProcessed: #result?.accountsProcessed(), accrued: #result?.accrued(), "
                    + "alreadyAccrued: #result?.alreadyAccrued(), posted: #result?.posted(), "
                    + "carriedForward: #result?.carriedForward(), postingFailures: #result?.postingFailures()}")
    public InterestAccrualResult accrue(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return service.accrue(date);
    }
}
