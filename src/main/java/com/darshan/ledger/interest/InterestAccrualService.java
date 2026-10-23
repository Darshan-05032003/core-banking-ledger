package com.darshan.ledger.interest;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.interest.InterestAccrualRepository.EndOfDayBalance;
import com.darshan.ledger.interest.InterestAccrualRepository.UnpostedAccruals;
import com.darshan.ledger.ledger.GlAccount;
import com.darshan.ledger.ledger.GlAccountCode;
import com.darshan.ledger.ledger.GlAccountRepository;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.Posting;
import com.darshan.ledger.ledger.PostingLeg;
import com.darshan.ledger.ledger.PostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Accrues a day of interest for every open savings account and, on the last day of the month,
// pays out what has accrued. Accrual and payout are kept apart on purpose: the daily row is a
// record of what the bank owes, computed from the balance the account held when that day ended,
// at the rate in force that day; the payout is the only step that moves money, and it goes
// through PostingService like everything else. Both steps are safe to repeat: the unique key on
// (account, date) turns a second accrual into a no-op, and a payout only takes rows that no
// journal has claimed yet.
@Service
public class InterestAccrualService {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualService.class);

    private final InterestAccrualRepository repository;
    private final AccountRepository accountRepository;
    private final GlAccountRepository glAccountRepository;
    private final PostingService postingService;
    private final InterestProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public InterestAccrualService(InterestAccrualRepository repository, AccountRepository accountRepository,
            GlAccountRepository glAccountRepository, PostingService postingService, InterestProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.glAccountRepository = glAccountRepository;
        this.postingService = postingService;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public InterestAccrualResult accrue(LocalDate accrualDate) {
        requireEnded(accrualDate);
        Instant cutoff = accrualDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        BigDecimal rate = properties.savingsAnnualRate();
        int processed = 0;
        int accrued = 0;
        long afterAccountId = 0;
        AccrualBatch batch;
        do {
            long from = afterAccountId;
            batch = transaction.execute(status -> accrueBatch(accrualDate, cutoff, rate, from));
            processed += batch.size();
            accrued += batch.inserted();
            afterAccountId = batch.lastAccountId(afterAccountId);
        } while (batch.size() == BATCH_SIZE);
        int alreadyAccrued = processed - accrued;
        log.info("Interest accrued for {}: {} accounts, {} new rows, {} already accrued", accrualDate, processed,
                accrued, alreadyAccrued);

        if (!isLastDayOfMonth(accrualDate)) {
            return new InterestAccrualResult(accrualDate, processed, accrued, alreadyAccrued, false, 0, 0, 0);
        }
        PostingTally tally = postAccruedUpTo(accrualDate);
        return new InterestAccrualResult(accrualDate, processed, accrued, alreadyAccrued, true, tally.posted,
                tally.carriedForward, tally.failed);
    }

    // A day can only be accrued once it is over: the balance of a day that is still running is
    // not final, and a row written now would fix the wrong figure for good.
    private void requireEnded(LocalDate accrualDate) {
        LocalDate today = LocalDate.now(clock);
        if (!accrualDate.isBefore(today)) {
            throw new BusinessRuleException(InterestErrorCode.ACCRUAL_DATE_NOT_ENDED,
                    "Interest for " + accrualDate + " cannot be accrued before the day has ended (today is " + today
                            + ")");
        }
    }

    // The amount is computed here and not in SQL because PostgreSQL's round() is half-up and
    // the ledger's rule is HALF_EVEN. Each account's row records the rate it was computed with,
    // so a later rate change leaves history as it was.
    private AccrualBatch accrueBatch(LocalDate accrualDate, Instant cutoff, BigDecimal rate, long afterAccountId) {
        List<EndOfDayBalance> balances = repository.endOfDayBalances(accrualDate, cutoff, afterAccountId, BATCH_SIZE);
        Instant now = clock.instant();
        int inserted = 0;
        for (EndOfDayBalance balance : balances) {
            BigDecimal amount = DailyInterest.of(balance.balance(), rate);
            if (repository.insert(balance.accountId(), accrualDate, balance.balance(), rate, amount,
                    balance.currency(), now)) {
                inserted++;
            }
        }
        return new AccrualBatch(balances, inserted);
    }

    private PostingTally postAccruedUpTo(LocalDate upTo) {
        PostingTally tally = new PostingTally();
        for (Long accountId : repository.accountIdsWithUnpostedAccrualsUpTo(upTo)) {
            try {
                switch (transaction.execute(status -> postForAccount(accountId, upTo))) {
                    case POSTED -> tally.posted++;
                    case CARRIED_FORWARD -> tally.carriedForward++;
                    case NOTHING_TO_POST -> { }
                }
            } catch (RuntimeException ex) {
                tally.failed++;
                log.error("Interest for account {} up to {} could not be posted: {}", accountId, upTo,
                        ex.getMessage());
            }
        }
        log.info("Month-end interest posted up to {}: {} journals, {} accounts carried forward, {} failures", upTo,
                tally.posted, tally.carriedForward, tally.failed);
        return tally;
    }

    // One account per transaction, so an account whose balance row cannot be locked right now
    // does not hold up the others. The accrual rows are locked before the balance row; nothing
    // else locks them, so this adds no ordering a transfer or approval could conflict with.
    private PostingOutcome postForAccount(Long accountId, LocalDate upTo) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Account " + accountId + " has accruals but no row"));
        UnpostedAccruals unposted = repository.lockUnpostedUpTo(accountId, upTo);
        if (unposted.count() == 0) {
            return PostingOutcome.NOTHING_TO_POST;
        }
        Money amount = DailyInterest.toMoney(unposted.total(), account.getCurrency());
        if (!amount.isPositive()) {
            return PostingOutcome.CARRIED_FORWARD;
        }
        GlAccount interestExpense = glAccountRepository.require(GlAccountCode.INTEREST_EXPENSE);
        JournalEntry journal = postingService.post(Posting.of(JournalType.INTEREST,
                "Savings interest accrued to " + upTo,
                List.of(PostingLeg.debit(interestExpense, amount), PostingLeg.credit(account, amount))));
        int marked = repository.markPosted(accountId, upTo, journal.getId());
        if (marked != unposted.count()) {
            throw new IllegalStateException("Locked " + unposted.count() + " accrual rows for account " + accountId
                    + " but marked " + marked + " as posted");
        }
        log.info("Interest {} for account {} posted as {} from {} daily accruals", amount, account.getAccountNumber(),
                journal.getReference(), unposted.count());
        return PostingOutcome.POSTED;
    }

    private static boolean isLastDayOfMonth(LocalDate date) {
        return date.getDayOfMonth() == date.lengthOfMonth();
    }

    private record AccrualBatch(List<EndOfDayBalance> balances, int inserted) {

        int size() {
            return balances.size();
        }

        long lastAccountId(long previous) {
            return balances.isEmpty() ? previous : balances.getLast().accountId();
        }
    }

    private enum PostingOutcome { POSTED, CARRIED_FORWARD, NOTHING_TO_POST }

    private static final class PostingTally {
        private int posted;
        private int carriedForward;
        private int failed;
    }
}
