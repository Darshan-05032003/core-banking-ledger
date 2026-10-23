package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Reserves and frees money without posting anything. Every method works on a balance row the
// caller has already locked for this transaction (through PostingService.lockBalances), which is
// why they demand an existing transaction: changing available_balance without the row lock
// would race with transfers checking the same figure.
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holdRepository;

    public HoldService(HoldRepository holdRepository) {
        this.holdRepository = holdRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Hold place(Account account, AccountBalance lockedBalance, Long transferId, Money amount, Instant now,
            Instant expiresAt) {
        requireSameAccount(account.getId(), lockedBalance);
        lockedBalance.hold(amount);
        Hold hold = holdRepository.save(new Hold(account, transferId, amount, now, expiresAt));
        log.info("Hold {} of {} placed on account {} for transfer {}, expires {}", hold.getId(), amount,
                account.getAccountNumber(), transferId, expiresAt);
        return hold;
    }

    // Capturing hands the reserved amount back to the available balance for the posting that
    // follows in the same transaction, which debits it again. Seen from outside the transaction
    // the available balance never rises.
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(Hold hold, AccountBalance lockedBalance, Instant now) {
        requireSameAccount(hold.getAccount().getId(), lockedBalance);
        hold.capture(now);
        lockedBalance.releaseHold(hold.getAmount());
        log.info("Hold {} captured for transfer {}", hold.getId(), hold.getTransferId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Hold hold, AccountBalance lockedBalance, Instant now) {
        requireSameAccount(hold.getAccount().getId(), lockedBalance);
        hold.release(now);
        lockedBalance.releaseHold(hold.getAmount());
        log.info("Hold {} released for transfer {}", hold.getId(), hold.getTransferId());
    }

    public Optional<Hold> findActive(Long transferId) {
        return holdRepository.findByTransferIdAndStatus(transferId, HoldStatus.ACTIVE);
    }

    // Oldest expiry first, so a backlog is worked off in the order it became due.
    public List<Long> transferIdsOfHoldsExpiredAt(Instant now, int limit) {
        return holdRepository.findTransferIdsOfHoldsExpiredAt(now, PageRequest.of(0, limit));
    }

    private static void requireSameAccount(Long accountId, AccountBalance balance) {
        if (!accountId.equals(balance.getAccountId())) {
            throw new IllegalArgumentException("Balance row " + balance.getAccountId()
                    + " does not belong to account " + accountId);
        }
    }
}
