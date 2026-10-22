package com.darshan.ledger.common.idempotency;

import java.util.Objects;

// What a request gets back when it presents an Idempotency-Key: either it owns the key and must
// do the work, or the work was already done and the stored response is to be returned as-is.
public sealed interface IdempotencyClaim {

    record Acquired(long id) implements IdempotencyClaim {
    }

    record Replayed(StoredResponse response) implements IdempotencyClaim {

        public Replayed {
            Objects.requireNonNull(response, "response must not be null");
        }
    }
}
