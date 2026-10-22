package com.darshan.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.common.error.RateLimitExceededException;
import com.darshan.ledger.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TransferRateLimiterTests {

    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-22T10:00:00Z"));
    private final TransferRateLimiter limiter = new TransferRateLimiter(new SecurityProperties(
            new SecurityProperties.Jwt("core-banking-ledger", Duration.ofMinutes(15), null, null, true),
            new SecurityProperties.RateLimit(3)), clock);

    @Test
    void theLimitIsEnforcedPerUserAndTheRefusalSaysHowLongToWait() {
        limiter.acquire("asha");
        limiter.acquire("asha");
        limiter.acquire("asha");

        assertThatThrownBy(() -> limiter.acquire("asha"))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(20))
                .hasMessage("Too many requests, retry after 20 seconds");
        assertThatCode(() -> limiter.acquire("ravi")).doesNotThrowAnyException();
    }

    @Test
    void tokensComeBackAsTheClockAdvances() {
        limiter.acquire("asha");
        limiter.acquire("asha");
        limiter.acquire("asha");
        assertThatThrownBy(() -> limiter.acquire("asha")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(19));
        assertThatThrownBy(() -> limiter.acquire("asha")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(1));
        assertThatCode(() -> limiter.acquire("asha")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire("asha")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> {
            limiter.acquire("asha");
            limiter.acquire("asha");
            limiter.acquire("asha");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire("asha")).isInstanceOf(RateLimitExceededException.class);
    }

    private static final class AdjustableClock extends Clock {

        private Instant now;

        private AdjustableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
