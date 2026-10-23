package com.darshan.ledger.ledger;

import java.time.LocalDate;
import java.util.List;

// The control report of one end-of-day run. The counts are complete; the lists are capped, so a
// badly broken ledger produces a readable report rather than a dump.
public record EndOfDayCheckResult(
        LocalDate businessDate,
        long accountsChecked,
        long balanceMismatches,
        List<BalanceMismatch> mismatchedBalances,
        long journalsChecked,
        long unbalancedJournals,
        List<UnbalancedJournal> unbalanced,
        List<CurrencyTotals> totals,
        boolean passed) {

    public boolean totalsBalanced() {
        return totals.stream().allMatch(CurrencyTotals::balanced);
    }
}
