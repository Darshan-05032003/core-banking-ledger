package com.darshan.ledger.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.common.error.ConflictException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class IdempotencyServiceIntegrationTests {

    record Payment(String to, BigDecimal amount) {
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final Payment payment = new Payment("000100000015", new BigDecimal("100.00"));

    @Test
    void concurrentClaimsOfTheSameKeyLetExactlyOneRequestThrough() throws Exception {
        String key = UUID.randomUUID().toString();
        int requests = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit((Callable<Object>) () -> {
                    start.await();
                    try {
                        return idempotencyService.claim("alice", key, payment);
                    } catch (ConflictException ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(IdempotencyClaim.Acquired.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(requests - 1)
                    .allSatisfy(ex -> assertThat(((ConflictException) ex).getErrorCode())
                            .isEqualTo(IdempotencyErrorCode.REQUEST_IN_PROGRESS));
            assertThat(repository.findByRequesterAndKeyValue("alice", key)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aCompletedKeyIsReplayedWithTheStoredStatusAndBody() {
        String key = UUID.randomUUID().toString();
        IdempotencyClaim.Acquired acquired = (IdempotencyClaim.Acquired) idempotencyService.claim("alice", key, payment);

        idempotencyService.complete(acquired.id(), 201, Map.of("id", 42, "amount", new BigDecimal("100.00")));

        IdempotencyClaim replay = idempotencyService.claim("alice", key, payment);
        assertThat(replay).isInstanceOf(IdempotencyClaim.Replayed.class);
        StoredResponse stored = ((IdempotencyClaim.Replayed) replay).response();
        assertThat(stored.status()).isEqualTo(201);
        assertThat(objectMapper.readTree(stored.body()))
                .isEqualTo(objectMapper.readTree("{\"id\":42,\"amount\":100.00}"));
        assertThat(stored.body()).contains("100.00");
    }

    @Test
    void theSameKeyIsIndependentPerRequester() {
        String key = UUID.randomUUID().toString();

        assertThat(idempotencyService.claim("alice", key, payment)).isInstanceOf(IdempotencyClaim.Acquired.class);
        assertThat(idempotencyService.claim("bob", key, payment)).isInstanceOf(IdempotencyClaim.Acquired.class);
    }

    @Test
    void aReleasedKeyCanBeClaimedAgain() {
        String key = UUID.randomUUID().toString();
        IdempotencyClaim.Acquired first = (IdempotencyClaim.Acquired) idempotencyService.claim("alice", key, payment);
        assertThatThrownBy(() -> idempotencyService.claim("alice", key, payment)).isInstanceOf(ConflictException.class);

        idempotencyService.release(first.id());

        IdempotencyClaim.Acquired second = (IdempotencyClaim.Acquired) idempotencyService.claim("alice", key, payment);
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void aKeyAbandonedInProgressIsTakenOverAfterTheTimeout() {
        String key = UUID.randomUUID().toString();
        IdempotencyClaim.Acquired first = (IdempotencyClaim.Acquired) idempotencyService.claim("alice", key, payment);
        assertThatThrownBy(() -> idempotencyService.claim("alice", key, payment)).isInstanceOf(ConflictException.class);

        jdbcTemplate.update("update idempotency_key set created_at = created_at - interval '3 minutes' where id = ?",
                first.id());

        assertThat(idempotencyService.claim("alice", key, payment)).isEqualTo(new IdempotencyClaim.Acquired(first.id()));
        assertThatThrownBy(() -> idempotencyService.claim("alice", key, payment)).isInstanceOf(ConflictException.class);
    }

    @Test
    void storingANonJsonBodyIsRefusedByTheDatabase() {
        String key = UUID.randomUUID().toString();
        IdempotencyClaim.Acquired acquired = (IdempotencyClaim.Acquired) idempotencyService.claim("alice", key, payment);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update idempotency_key set status = 'COMPLETED', response_status = 201, response_body = ?::jsonb,"
                        + " completed_at = now() where id = ?", "not json", acquired.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
