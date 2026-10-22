package com.darshan.ledger.common.error;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends LedgerException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, CommonErrorCode.RATE_LIMIT_EXCEEDED,
                "Too many requests, retry after " + wholeSeconds(retryAfter) + " seconds");
        this.retryAfterSeconds = wholeSeconds(retryAfter);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Map<String, Object> extensions() {
        return Map.of("retryAfterSeconds", retryAfterSeconds);
    }

    // Retry-After is whole seconds; rounding up means a client that waits exactly that long
    // finds a token available.
    private static long wholeSeconds(Duration retryAfter) {
        long seconds = retryAfter.toSeconds();
        return retryAfter.toNanosPart() > 0 || seconds == 0 ? seconds + 1 : seconds;
    }
}
