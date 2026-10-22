package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.HoldService;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.Posting;
import com.darshan.ledger.ledger.PostingLeg;
import com.darshan.ledger.ledger.PostingService;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// The transactional heart of a transfer: resolve both accounts, lock their balance rows in
// ascending id order, run the rules on the locked state, then either post the journal or, above
// the approval threshold, reserve the money with a hold and park the transfer for a checker. The
// transfer row and the idempotency response are written in the same READ COMMITTED transaction.
// Row locks, not a stricter isolation level, are what keep two transfers from spending the same
// money, and the hold is placed under that same lock so the next transfer sees it.
@Component
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private final AccountRepository accountRepository;
    private final PostingService postingService;
    private final TransferRuleChain ruleChain;
    private final TransferRepository transferRepository;
    private final TransferReferenceGenerator referenceGenerator;
    private final TransferMapper transferMapper;
    private final IdempotencyService idempotencyService;
    private final HoldService holdService;
    private final ApprovalProperties approval;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TransferProcessor(AccountRepository accountRepository, PostingService postingService,
            TransferRuleChain ruleChain, TransferRepository transferRepository,
            TransferReferenceGenerator referenceGenerator, TransferMapper transferMapper,
            IdempotencyService idempotencyService, HoldService holdService, ApprovalProperties approval,
            CurrentUser currentUser, Clock clock) {
        this.accountRepository = accountRepository;
        this.postingService = postingService;
        this.ruleChain = ruleChain;
        this.transferRepository = transferRepository;
        this.referenceGenerator = referenceGenerator;
        this.transferMapper = transferMapper;
        this.idempotencyService = idempotencyService;
        this.holdService = holdService;
        this.approval = approval;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferOutcome process(long claimId, TransferRequest request) {
        Money amount = amountOf(request);
        AccountNumber fromNumber = parse(request.fromAccount(), "Sender");
        AccountNumber toNumber = parse(request.toAccount(), "Beneficiary");
        Account sender = accountRepository.findByAccountNumber(fromNumber).orElse(null);
        Account beneficiary = accountRepository.findByAccountNumber(toNumber).orElse(null);
        List<Account> accounts = Stream.of(sender, beneficiary).filter(Objects::nonNull).toList();
        Map<Long, AccountBalance> balances = postingService.lockBalances(accounts);

        TransferContext context = new TransferContext(request, amount, sender, beneficiary,
                sender == null ? null : balances.get(sender.getId()));
        TransferAttempt attempt = attemptOf(sender, beneficiary, amount, request.remarks());
        if (ruleChain.evaluate(context) instanceof TransferRuleResult.Rejected rejected) {
            throw TransferRejectedException.byRule(rejected, attempt);
        }

        String reference = referenceGenerator.next();
        if (requiresApproval(amount)) {
            return park(claimId, reference, sender, beneficiary, amount, request.remarks(),
                    balances.get(sender.getId()));
        }
        JournalEntry journal = post(reference, sender, beneficiary, amount, request.remarks(), attempt);
        Transfer transfer = transferRepository.save(Transfer.completed(reference, sender, beneficiary, amount,
                request.remarks(), journal, currentUser.name(), clock.instant()));
        TransferResponse response = transferMapper.toResponse(transfer);
        idempotencyService.complete(claimId, HttpStatus.CREATED.value(), response);
        log.info("Transfer {} completed: {} from {} to {} as {}", reference, amount, sender.getAccountNumber(),
                beneficiary.getAccountNumber(), journal.getReference());
        return new TransferOutcome.Created(response);
    }

    // Strictly above the threshold: a transfer of exactly the threshold goes straight through.
    private boolean requiresApproval(Money amount) {
        return amount.isGreaterThan(Money.of(approval.threshold(), amount.currency()));
    }

    // The money is reserved, not moved: the hold lowers the sender's available balance while the
    // ledger balance waits for the checker's decision. The stored 202 response is what a replay
    // of the same key gets back, whatever happens to the transfer later.
    private TransferOutcome park(long claimId, String reference, Account sender, Account beneficiary, Money amount,
            String remarks, AccountBalance senderBalance) {
        Instant now = clock.instant();
        Transfer transfer = transferRepository.save(Transfer.pendingApproval(reference, sender, beneficiary, amount,
                remarks, currentUser.name(), now));
        holdService.place(sender, senderBalance, transfer.getId(), amount, now, now.plus(approval.pendingExpiry()));
        TransferResponse response = transferMapper.toResponse(transfer);
        idempotencyService.complete(claimId, HttpStatus.ACCEPTED.value(), response);
        log.info("Transfer {} of {} from {} to {} is waiting for approval", reference, amount,
                sender.getAccountNumber(), beneficiary.getAccountNumber());
        return new TransferOutcome.PendingApproval(response);
    }

    private JournalEntry post(String reference, Account sender, Account beneficiary, Money amount, String remarks,
            TransferAttempt attempt) {
        String description = remarks == null ? "Transfer " + reference : "Transfer " + reference + ": " + remarks;
        try {
            return postingService.post(Posting.of(JournalType.TRANSFER, description,
                    List.of(PostingLeg.debit(sender, amount), PostingLeg.credit(beneficiary, amount))));
        } catch (LedgerException ex) {
            throw TransferRejectedException.wrap(ex, attempt);
        }
    }

    // A transfer row needs two distinct, real accounts; a request that fails that shape is refused
    // at the door and remembered only by its idempotency key.
    private static TransferAttempt attemptOf(Account sender, Account beneficiary, Money amount, String remarks) {
        if (sender == null || beneficiary == null || sender.getId().equals(beneficiary.getId())) {
            return null;
        }
        return new TransferAttempt(sender.getId(), beneficiary.getId(), amount, remarks);
    }

    private static AccountNumber parse(String number, String role) {
        try {
            return new AccountNumber(number);
        } catch (IllegalArgumentException ex) {
            throw TransferRejectedException.of(TransferErrorCode.INVALID_ACCOUNT_NUMBER,
                    role + " account number " + AccountNumber.mask(number) + " is not a valid account number");
        }
    }

    private static Money amountOf(TransferRequest request) {
        try {
            return Money.of(request.amount(), Currency.getInstance(request.currency()));
        } catch (IllegalArgumentException ex) {
            throw TransferRejectedException.of(AccountErrorCode.UNSUPPORTED_CURRENCY,
                    request.currency() + " is not a known ISO 4217 currency");
        }
    }
}
