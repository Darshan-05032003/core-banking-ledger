package com.darshan.ledger.transfer;

import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.common.error.ProblemDetails;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Writes what a rejected transfer leaves behind: a FAILED transfer row and the stored problem
// response for the idempotency key. It runs in its own transaction because the transaction that
// rejected the transfer is rolled back, and it is called only after that rollback, so it never
// needs a second connection while the first one is still held.
@Component
public class TransferFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransferFailureRecorder.class);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final TransferReferenceGenerator referenceGenerator;
    private final IdempotencyService idempotencyService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TransferFailureRecorder(TransferRepository transferRepository, AccountRepository accountRepository,
            TransferReferenceGenerator referenceGenerator, IdempotencyService idempotencyService,
            CurrentUser currentUser, Clock clock) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.referenceGenerator = referenceGenerator;
        this.idempotencyService = idempotencyService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferRejectedException record(long claimId, TransferRejectedException rejection) {
        TransferRejectedException recorded = rejection.attempt()
                .map(attempt -> recordFailedTransfer(rejection, attempt))
                .orElse(rejection);
        idempotencyService.complete(claimId, recorded.getStatus().value(), ProblemDetails.of(recorded));
        return recorded;
    }

    private TransferRejectedException recordFailedTransfer(TransferRejectedException rejection,
            TransferAttempt attempt) {
        Transfer failed = transferRepository.save(Transfer.failed(referenceGenerator.next(),
                accountRepository.getReferenceById(attempt.fromAccountId()),
                accountRepository.getReferenceById(attempt.toAccountId()),
                attempt.amount(), attempt.remarks(), rejection.getErrorCode(), rejection.getMessage(),
                currentUser.name(), clock.instant()));
        log.info("Transfer {} failed: {}", failed.getReference(), rejection.getErrorCode().code());
        return rejection.recordedAs(failed);
    }
}
