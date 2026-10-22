package com.darshan.ledger.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.InvalidRequestException;
import com.darshan.ledger.common.error.LedgerException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    record Payment(String to, BigDecimal amount) {
    }

    @Mock
    private IdempotencyKeyRepository repository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private IdempotencyService service;
    private final Payment payment = new Payment("000100000015", new BigDecimal("100.00"));

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, transactionManager, JsonMapper.builder().build(),
                new IdempotencyProperties(TIMEOUT), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void aMissingKeyIsRejectedBeforeAnythingIsStored(String key) {
        assertThatThrownBy(() -> service.claim("alice", key, payment))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(IdempotencyErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "slash/inside", "tab\tkey",
            "sixty-five-characters-is-one-more-than-the-limit-allows-xxxxxxxxxx"})
    void aMalformedKeyIsRejected(String key) {
        assertThatThrownBy(() -> service.claim("alice", key, payment))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(IdempotencyErrorCode.IDEMPOTENCY_KEY_INVALID);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theFirstRequestAcquiresTheKeyWithAHashOfItsPayload() {
        given(repository.saveAndFlush(any())).willAnswer(invocation -> withId(invocation.getArgument(0), 7L));

        IdempotencyClaim claim = service.claim("alice", "order-1", payment);

        assertThat(claim).isEqualTo(new IdempotencyClaim.Acquired(7L));
        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRequester()).isEqualTo("alice");
        assertThat(saved.getValue().getKeyValue()).isEqualTo("order-1");
        assertThat(saved.getValue().getRequestHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getValue().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().isCompleted()).isFalse();
    }

    @Test
    void theSameKeyWithADifferentPayloadIsRefused() {
        IdempotencyKey existing = existingKey(new Payment("000100000015", new BigDecimal("999.00")));
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(repository.findByRequesterAndKeyValue("alice", "order-1")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.claim("alice", "order-1", payment))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(IdempotencyErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(repository, never()).takeOverStale(anyLong(), any(), any());
    }

    @Test
    void aCompletedKeyReplaysTheStoredResponse() {
        IdempotencyKey existing = existingKey(payment);
        existing.complete(201, "{\"id\":12}", NOW);
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(repository.findByRequesterAndKeyValue("alice", "order-1")).willReturn(Optional.of(existing));

        IdempotencyClaim claim = service.claim("alice", "order-1", payment);

        assertThat(claim).isEqualTo(new IdempotencyClaim.Replayed(new StoredResponse(201, "{\"id\":12}")));
    }

    @Test
    void aKeyStillInProgressIsAConflictUnlessItHasGoneStale() {
        IdempotencyKey existing = existingKey(payment);
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(repository.findByRequesterAndKeyValue("alice", "order-1")).willReturn(Optional.of(existing));
        given(repository.takeOverStale(3L, NOW.minus(TIMEOUT), NOW)).willReturn(0, 1);

        assertThatThrownBy(() -> service.claim("alice", "order-1", payment))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(IdempotencyErrorCode.REQUEST_IN_PROGRESS);

        assertThat(service.claim("alice", "order-1", payment)).isEqualTo(new IdempotencyClaim.Acquired(3L));
    }

    @Test
    void aKeyReleasedBetweenTheInsertAndTheLookupAsksTheClientToRetry() {
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(repository.findByRequesterAndKeyValue("alice", "order-1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim("alice", "order-1", payment))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(IdempotencyErrorCode.REQUEST_IN_PROGRESS);
    }

    @Test
    void completingStoresTheStatusAndTheSerialisedBodyOnce() {
        IdempotencyKey existing = existingKey(payment);
        given(repository.findById(3L)).willReturn(Optional.of(existing));

        service.complete(3L, 201, Map.of("id", 12));

        assertThat(existing.isCompleted()).isTrue();
        assertThat(existing.storedResponse()).isEqualTo(new StoredResponse(201, "{\"id\":12}"));
        assertThat(existing.getCompletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.complete(3L, 201, Map.of("id", 13))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasingDeletesOnlyAKeyThatIsStillInProgress() {
        service.release(3L);

        verify(repository).deleteInProgress(3L);
    }

    @Test
    void thePayloadHashIgnoresNothingButInstanceIdentity() {
        given(repository.saveAndFlush(any())).willAnswer(invocation -> withId(invocation.getArgument(0), 1L));
        service.claim("alice", "k1", new Payment("000100000015", new BigDecimal("100.00")));
        service.claim("alice", "k2", new Payment("000100000015", new BigDecimal("100.00")));
        service.claim("alice", "k3", new Payment("000100000015", new BigDecimal("100.01")));

        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository, times(3)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(0).getRequestHash()).isEqualTo(saved.getAllValues().get(1).getRequestHash());
        assertThat(saved.getAllValues().get(0).getRequestHash()).isNotEqualTo(saved.getAllValues().get(2).getRequestHash());
    }

    private IdempotencyKey existingKey(Payment storedPayload) {
        given(repository.saveAndFlush(any())).willAnswer(invocation -> withId(invocation.getArgument(0), 3L));
        service.claim("alice", "order-1", storedPayload);
        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).saveAndFlush(saved.capture());
        reset(repository);
        return saved.getValue();
    }

    private static IdempotencyKey withId(IdempotencyKey key, long id) {
        ReflectionTestUtils.setField(key, "id", id);
        return key;
    }
}
