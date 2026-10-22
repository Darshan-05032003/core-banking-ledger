package com.darshan.ledger.approval;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.web.PageResponse;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.Hold;
import com.darshan.ledger.ledger.HoldRepository;
import com.darshan.ledger.ledger.HoldService;
import com.darshan.ledger.ledger.HoldStatus;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.LockTimeout;
import com.darshan.ledger.ledger.Posting;
import com.darshan.ledger.ledger.PostingLeg;
import com.darshan.ledger.ledger.PostingService;
import com.darshan.ledger.security.CurrentUser;
import com.darshan.ledger.transfer.Transfer;
import com.darshan.ledger.transfer.TransferMapper;
import com.darshan.ledger.transfer.TransferRepository;
import com.darshan.ledger.transfer.TransferResponse;
import com.darshan.ledger.transfer.TransferStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// The checker's half of maker-checker. A decision first locks the transfer row, so two decisions
// on the same transfer are taken one after the other and the second finds nothing left to decide,
// then locks the balance rows in ascending account id like every other posting. Nothing ever
// locks a balance row and then a transfer row, so the two orders cannot form a deadlock.
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final TransferRepository transferRepository;
    private final HoldRepository holdRepository;
    private final HoldService holdService;
    private final PostingService postingService;
    private final LockTimeout lockTimeout;
    private final TransferMapper transferMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ApprovalService(TransferRepository transferRepository, HoldRepository holdRepository,
            HoldService holdService, PostingService postingService, LockTimeout lockTimeout,
            TransferMapper transferMapper, CurrentUser currentUser, Clock clock) {
        this.transferRepository = transferRepository;
        this.holdRepository = holdRepository;
        this.holdService = holdService;
        this.postingService = postingService;
        this.lockTimeout = lockTimeout;
        this.transferMapper = transferMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // Capturing the hold and posting the journal happen under one set of row locks in one
    // transaction, so the available balance ends exactly where a direct transfer would have left it.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse approve(Long transferId) {
        Transfer transfer = lockPending(transferId);
        String checker = requireDifferentUser(transfer);
        Hold hold = activeHoldOf(transfer);
        Account sender = transfer.getFromAccount();
        Account beneficiary = transfer.getToAccount();
        Map<Long, AccountBalance> balances = postingService.lockBalances(List.of(sender, beneficiary));
        sender.requireActive("debited");
        beneficiary.requireActive("credited");
        Instant now = clock.instant();

        holdService.capture(hold, balances.get(sender.getId()), now);
        JournalEntry journal = postingService.post(Posting.of(JournalType.TRANSFER, description(transfer),
                List.of(PostingLeg.debit(sender, transfer.getAmount()),
                        PostingLeg.credit(beneficiary, transfer.getAmount()))));
        transfer.approve(journal, checker, now);
        log.info("Transfer {} approved by {} and posted as {}", transfer.getReference(), checker,
                journal.getReference());
        return transferMapper.toResponse(transfer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse reject(Long transferId, RejectTransferRequest request) {
        Transfer transfer = lockPending(transferId);
        String checker = requireDifferentUser(transfer);
        Hold hold = activeHoldOf(transfer);
        Account sender = transfer.getFromAccount();
        Map<Long, AccountBalance> balances = postingService.lockBalances(List.of(sender));
        Instant now = clock.instant();

        holdService.release(hold, balances.get(sender.getId()), now);
        transfer.reject(checker, request.reason().trim(), now);
        log.info("Transfer {} rejected by {}", transfer.getReference(), checker);
        return transferMapper.toResponse(transfer);
    }

    // Oldest first: a checker works through the queue in the order it was raised.
    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> list(TransferStatus status, int page, int size) {
        return PageResponse.of(transferRepository
                .findByStatusOrderByCreatedAtAscIdAsc(status, PageRequest.of(page, size))
                .map(transferMapper::toResponse));
    }

    private Transfer lockPending(Long transferId) {
        lockTimeout.applyToCurrentTransaction();
        Transfer transfer = transferRepository.lockById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));
        transfer.requirePendingApproval();
        return transfer;
    }

    // Four eyes: the person who raised the transfer may not be the person who decides it, whatever
    // roles they hold. Usernames are unique and never change, so comparing them is comparing users.
    private String requireDifferentUser(Transfer transfer) {
        String checker = currentUser.name();
        if (transfer.wasInitiatedBy(checker)) {
            throw new BusinessRuleException(ApprovalErrorCode.SELF_APPROVAL_FORBIDDEN,
                    "Transfer " + transfer.getReference() + " was initiated by you; another user must decide it");
        }
        return checker;
    }

    private Hold activeHoldOf(Transfer transfer) {
        return holdRepository.findByTransferIdAndStatus(transfer.getId(), HoldStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "Pending transfer " + transfer.getReference() + " has no active hold"));
    }

    private static String description(Transfer transfer) {
        String base = "Transfer " + transfer.getReference();
        return transfer.getRemarks() == null ? base : base + ": " + transfer.getRemarks();
    }
}
