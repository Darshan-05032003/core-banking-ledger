package com.darshan.ledger.audit;

import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Always its own transaction: the business transaction may have rolled back, and the record of
// the attempt must survive that. The aspect calls this only after the audited method has
// returned, so the business transaction's connection is already back in the pool and this one
// never waits for it.
@Component
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditLogWriter(AuditLogRepository auditLogRepository, ObjectMapper objectMapper, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog write(AuditEvent event) {
        String details = event.details() == null || event.details().isEmpty()
                ? null
                : objectMapper.writeValueAsString(event.details());
        return auditLogRepository.save(new AuditLog(clock.instant(), event.actor(), String.join(",", event.roles()),
                event.action(), event.targetType(), event.targetId(), event.outcome(), event.errorCode(),
                event.clientIp(), event.correlationId(), details));
    }
}
