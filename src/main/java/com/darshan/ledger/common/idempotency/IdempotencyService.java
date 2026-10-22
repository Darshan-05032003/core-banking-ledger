package com.darshan.ledger.common.idempotency;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// Makes a request safe to retry. The first request with a key claims it by inserting a row; a
// retry finds the row and gets the stored response back instead of doing the work twice. The
// claim is committed in its own short transaction before the work starts, so two concurrent
// requests with the same key are decided by the unique constraint, not by application code.
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final IdempotencyKeyRepository repository;
    private final TransactionTemplate requiresNew;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository repository, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper, IdempotencyProperties properties, Clock clock) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public IdempotencyClaim claim(String requester, String rawKey, Object payload) {
        String key = requireValidKey(rawKey);
        String hash = fingerprint(payload);
        Optional<Long> inserted = tryInsert(requester, key, hash);
        if (inserted.isPresent()) {
            return new IdempotencyClaim.Acquired(inserted.get());
        }
        IdempotencyKey existing = repository.findByRequesterAndKeyValue(requester, key)
                .orElseThrow(() -> inProgress(key));
        if (!existing.matches(hash)) {
            throw new BusinessRuleException(IdempotencyErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key '" + key + "' was already used for a different request");
        }
        if (existing.isCompleted()) {
            log.info("Replaying the stored response for idempotency key {}", existing.getId());
            return new IdempotencyClaim.Replayed(existing.storedResponse());
        }
        if (takeOverIfStale(existing.getId())) {
            log.warn("Idempotency key {} was left in progress for too long and has been taken over", existing.getId());
            return new IdempotencyClaim.Acquired(existing.getId());
        }
        throw inProgress(key);
    }

    // Joins the caller's transaction on purpose: the stored response and the work it describes
    // are committed together or not at all.
    @Transactional
    public void complete(long claimId, int status, Object body) {
        IdempotencyKey key = repository.findById(claimId)
                .orElseThrow(() -> new IllegalStateException("Idempotency key " + claimId + " has disappeared"));
        key.complete(status, toJson(body), clock.instant());
    }

    // After an unexpected failure the key is handed back so the client can retry with it.
    public void release(long claimId) {
        requiresNew.executeWithoutResult(status -> repository.deleteInProgress(claimId));
    }

    private Optional<Long> tryInsert(String requester, String key, String hash) {
        try {
            return Optional.ofNullable(requiresNew.execute(status -> repository
                    .saveAndFlush(new IdempotencyKey(requester, key, hash, clock.instant()))
                    .getId()));
        } catch (DataIntegrityViolationException ex) {
            return Optional.empty();
        }
    }

    private boolean takeOverIfStale(long claimId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.inProgressTimeout());
        Integer updated = requiresNew.execute(status -> repository.takeOverStale(claimId, cutoff, now));
        return updated != null && updated == 1;
    }

    private static ConflictException inProgress(String key) {
        return new ConflictException(IdempotencyErrorCode.REQUEST_IN_PROGRESS,
                "A request with Idempotency-Key '" + key + "' is still being processed, retry shortly");
    }

    private static String requireValidKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new InvalidRequestException(IdempotencyErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "The " + HEADER + " header is required");
        }
        if (!KEY_FORMAT.matcher(rawKey).matches()) {
            throw new InvalidRequestException(IdempotencyErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "The " + HEADER + " header must be 1 to 64 letters, digits, dots, underscores, colons or dashes");
        }
        return rawKey;
    }

    private String fingerprint(Object payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(toJson(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String toJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
