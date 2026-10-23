package com.darshan.ledger.ledger;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.web.CorrelationIdFilter;
import com.darshan.ledger.ledger.EndOfDayCheckRepository.MismatchPage;
import com.darshan.ledger.ledger.EndOfDayCheckRepository.UnbalancedPage;
import com.darshan.ledger.security.CurrentUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// Reconciles the ledger against itself. The balance rows are a copy of what the lines say, kept
// in step by the posting transaction; this check is the independent proof that the copy is still
// right, and that nothing has reached the ledger tables behind the posting service's back. All
// the reads happen in one REPEATABLE READ snapshot, so a posting that commits while the check
// runs cannot show up as a false mismatch between two queries. Findings are reported three
// ways, because three different people look for them: an ERROR line for whoever is on call, a
// counter for the dashboards and alerts, and audit rows for the auditor.
@Service
public class EndOfDayCheck {

    public static final String MISMATCH_METRIC = "ledger.eod.mismatches";
    static final String BALANCE_MISMATCH_ACTION = "EOD_BALANCE_MISMATCH";
    static final String UNBALANCED_JOURNAL_ACTION = "EOD_JOURNAL_UNBALANCED";
    static final String TOTALS_MISMATCH_ACTION = "EOD_TOTALS_MISMATCH";
    static final int REPORT_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(EndOfDayCheck.class);

    private final EndOfDayCheckRepository repository;
    private final AuditLogWriter auditLogWriter;
    private final CurrentUser currentUser;
    private final TransactionTemplate snapshot;
    private final Counter balanceMismatches;
    private final Counter unbalancedJournals;
    private final Counter totalsMismatches;
    private final Clock clock;

    public EndOfDayCheck(EndOfDayCheckRepository repository, AuditLogWriter auditLogWriter, CurrentUser currentUser,
            PlatformTransactionManager transactionManager, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.auditLogWriter = auditLogWriter;
        this.currentUser = currentUser;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.balanceMismatches = counter(meterRegistry, "balance");
        this.unbalancedJournals = counter(meterRegistry, "journal");
        this.totalsMismatches = counter(meterRegistry, "totals");
        this.clock = clock;
    }

    public EndOfDayCheckResult run() {
        return run(LocalDate.now(clock));
    }

    public EndOfDayCheckResult run(LocalDate businessDate) {
        requireNotInFuture(businessDate);
        EndOfDayCheckResult result = snapshot.execute(status -> inspect(businessDate));
        report(result);
        return result;
    }

    private void requireNotInFuture(LocalDate businessDate) {
        LocalDate today = LocalDate.now(clock);
        if (businessDate.isAfter(today)) {
            throw new BusinessRuleException(LedgerErrorCode.BUSINESS_DATE_IN_FUTURE,
                    "The end-of-day check cannot run for " + businessDate + " before that day (today is " + today + ")");
        }
    }

    private EndOfDayCheckResult inspect(LocalDate businessDate) {
        long accounts = repository.countBalances();
        MismatchPage mismatches = repository.balanceMismatches(REPORT_LIMIT);
        long journals = repository.countJournals();
        UnbalancedPage unbalanced = repository.unbalancedJournals(REPORT_LIMIT);
        List<CurrencyTotals> totals = repository.currencyTotals();
        boolean passed = mismatches.total() == 0 && unbalanced.total() == 0
                && totals.stream().allMatch(CurrencyTotals::balanced);
        return new EndOfDayCheckResult(businessDate, accounts, mismatches.total(), mismatches.rows(), journals,
                unbalanced.total(), unbalanced.rows(), totals, passed);
    }

    // Reporting happens after the snapshot transaction has ended: the audit writer opens its own
    // transaction, and it must not do so while this one still holds a connection.
    private void report(EndOfDayCheckResult result) {
        if (result.passed()) {
            log.info("End-of-day check for {} passed: {} accounts, {} journals, totals balanced in {} currencies",
                    result.businessDate(), result.accountsChecked(), result.journalsChecked(), result.totals().size());
            return;
        }
        for (BalanceMismatch mismatch : result.mismatchedBalances()) {
            log.error("End-of-day check for {}: account {} has ledger balance {} but its lines sum to {},"
                            + " available balance {} but ledger minus holds is {}", result.businessDate(),
                    mismatch.maskedAccountNumber(), mismatch.actualLedgerBalance(), mismatch.expectedLedgerBalance(),
                    mismatch.actualAvailableBalance(), mismatch.expectedAvailableBalance());
            balanceMismatches.increment();
            audit(BALANCE_MISMATCH_ACTION, "ACCOUNT", String.valueOf(mismatch.accountId()),
                    LedgerErrorCode.BALANCE_MISMATCH, balanceDetails(mismatch));
        }
        for (UnbalancedJournal journal : result.unbalanced()) {
            log.error("End-of-day check for {}: journal {} has {} legs with debits {} and credits {}",
                    result.businessDate(), journal.reference(), journal.legs(), journal.debits(), journal.credits());
            unbalancedJournals.increment();
            audit(UNBALANCED_JOURNAL_ACTION, "JOURNAL", String.valueOf(journal.journalId()),
                    LedgerErrorCode.UNBALANCED_JOURNAL, Map.of("reference", journal.reference(), "legs",
                            journal.legs(), "debits", journal.debits(), "credits", journal.credits()));
        }
        for (CurrencyTotals totals : result.totals()) {
            if (!totals.balanced()) {
                log.error("End-of-day check for {}: {} debits {} do not equal credits {}", result.businessDate(),
                        totals.currency(), totals.debits(), totals.credits());
                totalsMismatches.increment();
                audit(TOTALS_MISMATCH_ACTION, "LEDGER", totals.currency(), LedgerErrorCode.LEDGER_OUT_OF_BALANCE,
                        Map.of("debits", totals.debits(), "credits", totals.credits()));
            }
        }
        log.error("End-of-day check for {} failed: {} of {} balances mismatched, {} of {} journals unbalanced,"
                        + " totals balanced: {}", result.businessDate(), result.balanceMismatches(),
                result.accountsChecked(), result.unbalancedJournals(), result.journalsChecked(),
                result.totalsBalanced());
    }

    private void audit(String action, String targetType, String targetId, LedgerErrorCode errorCode,
            Map<String, Object> details) {
        auditLogWriter.write(new AuditEvent(currentUser.name(), currentUser.roles(), action, targetType, targetId,
                AuditOutcome.FAILURE, errorCode.code(), null, MDC.get(CorrelationIdFilter.MDC_KEY), details));
    }

    private static Map<String, Object> balanceDetails(BalanceMismatch mismatch) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("account", mismatch.maskedAccountNumber());
        details.put("currency", mismatch.currency());
        details.put("expectedLedgerBalance", mismatch.expectedLedgerBalance());
        details.put("actualLedgerBalance", mismatch.actualLedgerBalance());
        details.put("expectedAvailableBalance", mismatch.expectedAvailableBalance());
        details.put("actualAvailableBalance", mismatch.actualAvailableBalance());
        return details;
    }

    private static Counter counter(MeterRegistry registry, String type) {
        return Counter.builder(MISMATCH_METRIC)
                .description("Discrepancies found by the end-of-day ledger check")
                .tag("type", type)
                .register(registry);
    }
}
