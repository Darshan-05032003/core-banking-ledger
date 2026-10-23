package com.darshan.ledger.ledger;

import static com.darshan.ledger.ledger.LedgerFixtures.INR;
import static com.darshan.ledger.ledger.LedgerFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.common.money.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class HoldTests {

    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(24));

    @Test
    void aHoldMustExpireAfterItIsPlaced() {
        assertThatThrownBy(() -> hold(NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hold(NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(hold(NOW.plusSeconds(1)).isActive()).isTrue();
    }

    @Test
    void aHoldIsExpiredFromTheExactExpiryInstantOn() {
        Hold hold = hold(EXPIRES_AT);

        assertThat(hold.isExpiredAt(EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(hold.isExpiredAt(EXPIRES_AT)).isTrue();
        assertThat(hold.isExpiredAt(EXPIRES_AT.plusSeconds(1))).isTrue();
    }

    @Test
    void identityIsTheDatabaseIdAndTheHashDoesNotChangeWhenItIsAssigned() {
        Hold first = hold(EXPIRES_AT);
        Hold second = hold(EXPIRES_AT);
        int hashBeforeSaving = first.hashCode();

        assertThat(first).isEqualTo(first).isNotEqualTo(second).isNotEqualTo(null).isNotEqualTo("hold");

        ReflectionTestUtils.setField(first, "id", 9L);
        ReflectionTestUtils.setField(second, "id", 9L);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.hashCode()).isEqualTo(hashBeforeSaving);
    }

    private static Hold hold(Instant expiresAt) {
        return new Hold(LedgerFixtures.account(5L, INR), 42L, Money.of("250.00", INR), NOW, expiresAt);
    }
}
