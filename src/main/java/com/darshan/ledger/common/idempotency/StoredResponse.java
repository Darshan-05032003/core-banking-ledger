package com.darshan.ledger.common.idempotency;

import java.util.Objects;

// The HTTP status and JSON body the first request produced, returned as-is on every replay.
public record StoredResponse(int status, String body) {

    public StoredResponse {
        Objects.requireNonNull(body, "body must not be null");
    }

    public boolean isError() {
        return status >= 400;
    }
}
