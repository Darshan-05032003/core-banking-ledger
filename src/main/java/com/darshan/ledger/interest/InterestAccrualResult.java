package com.darshan.ledger.interest;

import java.time.LocalDate;

// What one run of the accrual did. alreadyAccrued counts the accounts whose row for the date
// existed before the run, which is how a repeated run reports that it changed nothing. The
// posting figures are zero on any day but the last of the month: posted is the number of
// INTEREST journals written, carriedForward the accounts whose accrued interest was still below
// one paisa and stays unposted until a later month-end.
public record InterestAccrualResult(
        LocalDate accrualDate,
        int accountsProcessed,
        int accrued,
        int alreadyAccrued,
        boolean monthEnd,
        int posted,
        int carriedForward,
        int postingFailures) {
}
