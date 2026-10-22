package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.RateLimitExceededException;
import com.darshan.ledger.security.SecurityProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// One token bucket per user for the transfer endpoint. The bucket refills continuously (greedy):
// with a limit of 20 a minute a new token arrives every three seconds, so a client that is
// refused learns exactly how long to wait. Time comes from the application clock, which is what
// makes the behaviour testable without sleeping.
@Component
public class TransferRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TransferRateLimiter.class);
    private static final Duration PERIOD = Duration.ofMinutes(1);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int transfersPerMinute;
    private final TimeMeter timeMeter;

    public TransferRateLimiter(SecurityProperties properties, Clock clock) {
        this.transfersPerMinute = properties.rateLimit().transfersPerMinute();
        this.timeMeter = new ClockTimeMeter(clock);
    }

    public void acquire(String user) {
        ConsumptionProbe probe = buckets.computeIfAbsent(user, ignored -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
            log.info("Transfer rate limit reached for user {}", user);
            throw new RateLimitExceededException(retryAfter);
        }
    }

    private Bucket newBucket() {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(transfersPerMinute)
                .refillGreedy(transfersPerMinute, PERIOD)
                .build();
        return Bucket.builder()
                .addLimit(bandwidth)
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
