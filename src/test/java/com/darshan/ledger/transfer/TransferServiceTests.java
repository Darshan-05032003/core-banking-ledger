package com.darshan.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.idempotency.IdempotencyClaim;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.common.idempotency.StoredResponse;
import com.darshan.ledger.ledger.LedgerErrorCode;
import com.darshan.ledger.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class TransferServiceTests {

    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private TransferProcessor processor;
    @Mock
    private TransferFailureRecorder failureRecorder;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private CurrentUser currentUser;

    private TransferService service;
    private final TransferRequest raw = new TransferRequest("000100000023", "000100000057", new BigDecimal("250"),
            "INR", "  Rent ");
    private final TransferRequest normalised = new TransferRequest("000100000023", "000100000057",
            new BigDecimal("250.00"), "INR", "Rent");

    @BeforeEach
    void setUp() {
        service = new TransferService(idempotencyService, processor, failureRecorder, transferRepository,
                new TransferMapperImpl(), currentUser);
    }

    @Test
    void aNewKeyRunsTheTransferOnTheNormalisedRequest() {
        given(currentUser.name()).willReturn("alice");
        given(idempotencyService.claim("alice", "order-1", normalised)).willReturn(new IdempotencyClaim.Acquired(7L));
        TransferOutcome created = new TransferOutcome.Created(response());
        given(processor.process(7L, normalised)).willReturn(created);

        TransferOutcome outcome = service.transfer("order-1", raw);

        assertThat(outcome).isSameAs(created);
        verifyNoInteractions(failureRecorder);
        verify(idempotencyService, never()).release(anyLong());
    }

    @Test
    void aParkedTransferComesBackAsPendingApproval() {
        given(currentUser.name()).willReturn("alice");
        given(idempotencyService.claim("alice", "order-1", normalised)).willReturn(new IdempotencyClaim.Acquired(7L));
        TransferOutcome pending = new TransferOutcome.PendingApproval(response());
        given(processor.process(7L, normalised)).willReturn(pending);

        assertThat(service.transfer("order-1", raw)).isSameAs(pending);
        verifyNoInteractions(failureRecorder);
    }

    @Test
    void aKnownKeyReplaysWithoutTouchingTheProcessor() {
        given(currentUser.name()).willReturn("alice");
        StoredResponse stored = new StoredResponse(201, "{\"id\":12}");
        given(idempotencyService.claim("alice", "order-1", normalised)).willReturn(new IdempotencyClaim.Replayed(stored));

        TransferOutcome outcome = service.transfer("order-1", raw);

        assertThat(outcome).isEqualTo(new TransferOutcome.Replayed(stored));
        verifyNoInteractions(processor, failureRecorder);
    }

    @Test
    void aRejectionIsRecordedAndRethrownWithTheRecordedReference() {
        given(currentUser.name()).willReturn("alice");
        given(idempotencyService.claim("alice", "order-1", normalised)).willReturn(new IdempotencyClaim.Acquired(7L));
        TransferRejectedException rejection = TransferRejectedException.byRule(
                new TransferRuleResult.Rejected(LedgerErrorCode.INSUFFICIENT_FUNDS, "short"), null);
        TransferRejectedException recorded = TransferRejectedException.of(LedgerErrorCode.INSUFFICIENT_FUNDS, "short");
        given(processor.process(7L, normalised)).willThrow(rejection);
        given(failureRecorder.record(7L, rejection)).willReturn(recorded);

        assertThatThrownBy(() -> service.transfer("order-1", raw)).isSameAs(recorded);
        verify(idempotencyService, never()).release(anyLong());
    }

    @Test
    void anUnexpectedFailureReleasesTheKeySoTheClientCanRetry() {
        given(currentUser.name()).willReturn("alice");
        given(idempotencyService.claim("alice", "order-1", normalised)).willReturn(new IdempotencyClaim.Acquired(7L));
        given(processor.process(7L, normalised)).willThrow(new CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service.transfer("order-1", raw)).isInstanceOf(CannotAcquireLockException.class);
        verify(idempotencyService).release(7L);
        verifyNoInteractions(failureRecorder);
    }

    @Test
    void fetchingAnUnknownTransferIs404() {
        given(transferRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static TransferResponse response() {
        Instant now = Instant.parse("2026-10-22T10:15:30Z");
        return new TransferResponse(12L, "TRF-0000000012", TransferStatus.COMPLETED, 23L, "XXXXXXXX0023", 57L,
                "XXXXXXXX0057", new BigDecimal("250.00"), "INR", "Rent", 40L, "JRN-0000000040", null, null, "alice",
                now, now, null, null);
    }
}
