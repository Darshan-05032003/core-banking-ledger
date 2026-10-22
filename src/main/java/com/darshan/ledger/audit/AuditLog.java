package com.darshan.ledger.audit;

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
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(nullable = false, updatable = false, length = 100)
    private String roles;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", updatable = false, length = 64)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private AuditOutcome outcome;

    @Column(name = "error_code", updatable = false, length = 50)
    private String errorCode;

    @Column(name = "client_ip", updatable = false, length = 45)
    private String clientIp;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String details;

    AuditLog(Instant occurredAt, String actor, String roles, String action, String targetType, String targetId,
            AuditOutcome outcome, String errorCode, String clientIp, String correlationId, String details) {
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.actor = Objects.requireNonNull(actor);
        this.roles = Objects.requireNonNull(roles);
        this.action = Objects.requireNonNull(action);
        this.targetType = Objects.requireNonNull(targetType);
        this.targetId = targetId;
        this.outcome = Objects.requireNonNull(outcome);
        this.errorCode = errorCode;
        this.clientIp = clientIp;
        this.correlationId = correlationId;
        this.details = details;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AuditLog log && id != null && id.equals(log.id));
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
