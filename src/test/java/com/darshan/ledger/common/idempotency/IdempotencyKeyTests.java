package com.darshan.ledger.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IdempotencyKeyTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");

    private final IdempotencyKey key = new IdempotencyKey("asha", "order-1", "hash-1", NOW);

    @Test
    void aFreshKeyIsInProgressAndHasNoResponseToReplay() {
        assertThat(key.isCompleted()).isFalse();
        assertThat(key.matches("hash-1")).isTrue();
        assertThat(key.matches("hash-2")).isFalse();
        assertThatThrownBy(key::storedResponse)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no stored response yet");
    }

    @Test
    void completingStoresTheResponseExactlyOnce() {
        key.complete(201, "{\"id\":12}", NOW.plusSeconds(1));

        assertThat(key.isCompleted()).isTrue();
        assertThat(key.storedResponse()).isEqualTo(new StoredResponse(201, "{\"id\":12}"));
        assertThatThrownBy(() -> key.complete(201, "{\"id\":13}", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already completed");
        assertThat(key.storedResponse().body()).isEqualTo("{\"id\":12}");
    }

    @Test
    void identityIsTheDatabaseIdAndTheHashDoesNotChangeWhenItIsAssigned() {
        IdempotencyKey other = new IdempotencyKey("asha", "order-1", "hash-1", NOW);
        int hashBeforeSaving = key.hashCode();

        assertThat(key).isEqualTo(key).isNotEqualTo(other).isNotEqualTo(null).isNotEqualTo("key");

        ReflectionTestUtils.setField(key, "id", 3L);
        ReflectionTestUtils.setField(other, "id", 3L);

        assertThat(key).isEqualTo(other).hasSameHashCodeAs(other);
        assertThat(key.hashCode()).isEqualTo(hashBeforeSaving);
    }
}
