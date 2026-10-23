package com.darshan.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.common.idempotency.StoredResponse;
import com.darshan.ledger.ledger.LedgerErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class TransferMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TransferMetrics metrics = new TransferMetrics(registry);

    @Test
    void aCompletedTransferCountsAsCompletedAndIsTimedUnderThatOutcome() {
        TransferOutcome created = new TransferOutcome.Created(response());

        assertThat(metrics.measure(() -> created)).isSameAs(created);

        assertThat(completed()).isEqualTo(1.0);
        assertThat(latencyCount("completed")).isEqualTo(1);
        assertThat(latencyCount("pending_approval")).isZero();
    }

    @Test
    void aParkedTransferIsTimedButNotCountedAsCompleted() {
        metrics.measure(() -> new TransferOutcome.PendingApproval(response()));

        assertThat(completed()).isZero();
        assertThat(latencyCount("pending_approval")).isEqualTo(1);
    }

    @Test
    void aReplayIsTimedSeparately() {
        metrics.measure(() -> new TransferOutcome.Replayed(new StoredResponse(201, "{}")));

        assertThat(completed()).isZero();
        assertThat(latencyCount("replayed")).isEqualTo(1);
    }

    @Test
    void aRuleRejectionIsCountedUnderItsErrorCodeAndRethrown() {
        TransferRejectedException rejection = TransferRejectedException.of(LedgerErrorCode.INSUFFICIENT_FUNDS, "short");

        assertThatThrownBy(() -> metrics.measure(() -> {
            throw rejection;
        })).isSameAs(rejection);

        assertThat(failed("INSUFFICIENT_FUNDS")).isEqualTo(1.0);
        assertThat(latencyCount("rejected")).isEqualTo(1);
        assertThat(completed()).isZero();
    }

    @Test
    void anUnexpectedFailureIsTimedAsAnErrorAndCountedAsNoFailureReason() {
        assertThatThrownBy(() -> metrics.measure(() -> {
            throw new CannotAcquireLockException("lock timeout");
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(latencyCount("error")).isEqualTo(1);
        assertThat(registry.find(TransferMetrics.FAILED).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void everyTransferErrorCodeIsRegisteredAtZeroBeforeAnyRefusal() {
        for (TransferErrorCode code : TransferErrorCode.values()) {
            assertThat(failed(code.code())).as(code.code()).isZero();
        }
    }

    @Test
    void anApprovedTransferCountsAsCompleted() {
        metrics.completed();

        assertThat(completed()).isEqualTo(1.0);
    }

    private double completed() {
        return registry.get(TransferMetrics.COMPLETED).counter().count();
    }

    private double failed(String errorCode) {
        return registry.get(TransferMetrics.FAILED).tag(TransferMetrics.ERROR_CODE_TAG, errorCode).counter().count();
    }

    private long latencyCount(String outcome) {
        return registry.get(TransferMetrics.LATENCY).tag(TransferMetrics.OUTCOME_TAG, outcome).timer().count();
    }

    private static TransferResponse response() {
        Instant now = Instant.parse("2026-10-22T10:15:30Z");
        return new TransferResponse(12L, "TRF-0000000012", TransferStatus.COMPLETED, 23L, "XXXXXXXX0023", 57L,
                "XXXXXXXX0057", new BigDecimal("250.00"), "INR", "Rent", 40L, "JRN-0000000040", null, null, "alice",
                now, now, null, null);
    }
}
