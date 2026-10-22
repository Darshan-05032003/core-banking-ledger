package com.darshan.ledger.transfer;

import com.darshan.ledger.common.idempotency.StoredResponse;
import java.util.Objects;

// Either this request performed the transfer, parked it for a checker, or an earlier request
// with the same key did one of those and its response is returned unchanged.
public sealed interface TransferOutcome {

    record Created(TransferResponse response) implements TransferOutcome {

        public Created {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    record PendingApproval(TransferResponse response) implements TransferOutcome {

        public PendingApproval {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    record Replayed(StoredResponse response) implements TransferOutcome {

        public Replayed {
            Objects.requireNonNull(response, "response must not be null");
        }
    }
}
