package com.darshan.ledger.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 100)
    private String requester;

    @Column(name = "key_value", nullable = false, updatable = false, length = 64)
    private String keyValue;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    IdempotencyKey(String requester, String keyValue, String requestHash, Instant createdAt) {
        this.requester = Objects.requireNonNull(requester);
        this.keyValue = Objects.requireNonNull(keyValue);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    void complete(int responseStatus, String responseBody, Instant completedAt) {
        if (status == IdempotencyStatus.COMPLETED) {
            throw new IllegalStateException("Idempotency key " + id + " is already completed");
        }
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = Objects.requireNonNull(responseBody);
        this.completedAt = Objects.requireNonNull(completedAt);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String hash) {
        return requestHash.equals(hash);
    }

    public StoredResponse storedResponse() {
        if (!isCompleted()) {
            throw new IllegalStateException("Idempotency key " + id + " has no stored response yet");
        }
        return new StoredResponse(responseStatus, responseBody);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof IdempotencyKey that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return IdempotencyKey.class.hashCode();
    }
}
