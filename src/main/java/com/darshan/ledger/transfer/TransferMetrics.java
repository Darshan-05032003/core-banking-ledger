package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// What the dashboards see of the transfer engine: how many transfers were posted, how many were
// refused and for which reason, and how long a request took by outcome. Every tag value comes
// from an enum, so the number of time series is fixed however many requests arrive; a tag built
// from an account number or a username would create a series per customer and sink the metrics
// store.
@Component
public class TransferMetrics {

    public static final String COMPLETED = "transfers.completed";
    public static final String FAILED = "transfers.failed";
    public static final String LATENCY = "transfers.latency";
    public static final String ERROR_CODE_TAG = "errorCode";
    public static final String OUTCOME_TAG = "outcome";

    static final String OUTCOME_COMPLETED = "completed";
    static final String OUTCOME_PENDING_APPROVAL = "pending_approval";
    static final String OUTCOME_REPLAYED = "replayed";
    static final String OUTCOME_REJECTED = "rejected";
    static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;
    private final Counter completed;
    private final Map<String, Timer> latencyByOutcome;
    private final Map<String, Counter> failedByCode = new ConcurrentHashMap<>();

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.completed = Counter.builder(COMPLETED)
                .description("Transfers posted to the ledger, directly or after approval")
                .register(registry);
        this.latencyByOutcome = List.of(OUTCOME_COMPLETED, OUTCOME_PENDING_APPROVAL, OUTCOME_REPLAYED,
                        OUTCOME_REJECTED, OUTCOME_ERROR).stream()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), this::latencyTimer));
        // Registered up front so a fresh instance reports zero for every known reason instead of
        // having no series at all until the first refusal.
        for (TransferErrorCode code : TransferErrorCode.values()) {
            failedCounter(code);
        }
    }

    public void completed() {
        completed.increment();
    }

    public TransferOutcome measure(Supplier<TransferOutcome> processing) {
        Timer.Sample sample = Timer.start(registry);
        try {
            TransferOutcome outcome = processing.get();
            switch (outcome) {
                case TransferOutcome.Created created -> {
                    completed.increment();
                    sample.stop(latencyByOutcome.get(OUTCOME_COMPLETED));
                }
                case TransferOutcome.PendingApproval pending -> sample.stop(latencyByOutcome.get(OUTCOME_PENDING_APPROVAL));
                case TransferOutcome.Replayed replayed -> sample.stop(latencyByOutcome.get(OUTCOME_REPLAYED));
            }
            return outcome;
        } catch (TransferRejectedException ex) {
            failedCounter(ex.getErrorCode()).increment();
            sample.stop(latencyByOutcome.get(OUTCOME_REJECTED));
            throw ex;
        } catch (RuntimeException ex) {
            sample.stop(latencyByOutcome.get(OUTCOME_ERROR));
            throw ex;
        }
    }

    private Counter failedCounter(ErrorCode code) {
        return failedByCode.computeIfAbsent(code.code(), value -> Counter.builder(FAILED)
                .description("Transfers refused by a rule, by the reason given to the client")
                .tag(ERROR_CODE_TAG, value)
                .register(registry));
    }

    // A histogram rather than client-side percentiles, so a p99 can be computed across instances.
    private Timer latencyTimer(String outcome) {
        return Timer.builder(LATENCY)
                .description("Time to answer a transfer request, from the idempotency claim to the response")
                .tag(OUTCOME_TAG, outcome)
                .publishPercentileHistogram()
                .register(registry);
    }
}
