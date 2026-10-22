package com.darshan.ledger.transfer;

import com.darshan.ledger.common.idempotency.StoredResponse;
import java.util.Objects;

// Either this request performed the transfer, or an earlier request with the same key did and
// its response is returned unchanged.
public sealed interface TransferOutcome {

    record Created(TransferResponse response) implements TransferOutcome {

        public Created {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    record Replayed(StoredResponse response) implements TransferOutcome {

        public Replayed {
            Objects.requireNonNull(response, "response must not be null");
        }
    }
}
