package com.darshan.ledger.approval;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.Hold;
import com.darshan.ledger.ledger.HoldRepository;
import com.darshan.ledger.ledger.HoldService;
import com.darshan.ledger.ledger.HoldStatus;
import com.darshan.ledger.ledger.LockTimeout;
import com.darshan.ledger.ledger.PostingService;
import com.darshan.ledger.security.CurrentUser;
import com.darshan.ledger.transfer.Transfer;
import com.darshan.ledger.transfer.TransferRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// Expires one transfer per transaction, so a transfer that cannot be locked right now holds up
// nothing else. The same locks as a checker's decision are taken in the same order, and the
// state is re-read under them: a checker may have decided the transfer a moment earlier.
@Component
public class PendingTransferExpirer {

    private static final Logger log = LoggerFactory.getLogger(PendingTransferExpirer.class);

    private final TransferRepository transferRepository;
    private final HoldRepository holdRepository;
    private final HoldService holdService;
    private final PostingService postingService;
    private final LockTimeout lockTimeout;

    public PendingTransferExpirer(TransferRepository transferRepository, HoldRepository holdRepository,
            HoldService holdService, PostingService postingService, LockTimeout lockTimeout) {
        this.transferRepository = transferRepository;
        this.holdRepository = holdRepository;
        this.holdService = holdService;
        this.postingService = postingService;
        this.lockTimeout = lockTimeout;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ExpiredTransfer> expire(Long transferId, Instant now) {
        lockTimeout.applyToCurrentTransaction();
        Transfer transfer = transferRepository.lockById(transferId).orElse(null);
        if (transfer == null || !transfer.isPendingApproval()) {
            return Optional.empty();
        }
        Hold hold = holdRepository.findByTransferIdAndStatus(transferId, HoldStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "Pending transfer " + transfer.getReference() + " has no active hold"));
        if (!hold.isExpiredAt(now)) {
            return Optional.empty();
        }
        Account sender = transfer.getFromAccount();
        Map<Long, AccountBalance> balances = postingService.lockBalances(List.of(sender));
        holdService.release(hold, balances.get(sender.getId()), now);
        transfer.expire(CurrentUser.SYSTEM, now);
        log.info("Transfer {} expired unapproved after {}", transfer.getReference(), hold.getExpiresAt());
        return Optional.of(new ExpiredTransfer(transfer.getId(), transfer.getReference(), hold.getId(),
                hold.getAmount()));
    }
}
