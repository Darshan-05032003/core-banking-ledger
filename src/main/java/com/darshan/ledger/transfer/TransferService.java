package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.idempotency.IdempotencyClaim;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Deliberately not transactional as a whole: claiming the key, performing the transfer and
// recording a failure are separate transactions, each committed before the next one starts.
@Service
public class TransferService {

    private final IdempotencyService idempotencyService;
    private final TransferProcessor processor;
    private final TransferFailureRecorder failureRecorder;
    private final TransferRepository transferRepository;
    private final TransferMapper transferMapper;
    private final CurrentUser currentUser;

    public TransferService(IdempotencyService idempotencyService, TransferProcessor processor,
            TransferFailureRecorder failureRecorder, TransferRepository transferRepository,
            TransferMapper transferMapper, CurrentUser currentUser) {
        this.idempotencyService = idempotencyService;
        this.processor = processor;
        this.failureRecorder = failureRecorder;
        this.transferRepository = transferRepository;
        this.transferMapper = transferMapper;
        this.currentUser = currentUser;
    }

    public TransferOutcome transfer(String idempotencyKey, TransferRequest request) {
        TransferRequest normalised = request.normalised();
        IdempotencyClaim claim = idempotencyService.claim(currentUser.name(), idempotencyKey, normalised);
        return switch (claim) {
            case IdempotencyClaim.Replayed replayed -> new TransferOutcome.Replayed(replayed.response());
            case IdempotencyClaim.Acquired acquired -> new TransferOutcome.Created(execute(acquired.id(), normalised));
        };
    }

    @Transactional(readOnly = true)
    public TransferResponse get(Long transferId) {
        return transferRepository.findById(transferId)
                .map(transferMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));
    }

    // A rejection is a business outcome: it is recorded and replayed like a success. Anything
    // else (a lock timeout, a lost connection) hands the key back so the client can retry.
    private TransferResponse execute(long claimId, TransferRequest request) {
        try {
            return processor.process(claimId, request);
        } catch (TransferRejectedException ex) {
            throw failureRecorder.record(claimId, ex);
        } catch (RuntimeException ex) {
            idempotencyService.release(claimId);
            throw ex;
        }
    }
}
