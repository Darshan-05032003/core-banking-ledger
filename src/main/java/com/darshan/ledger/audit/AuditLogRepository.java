package com.darshan.ledger.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTargetTypeAndTargetIdOrderByIdAsc(String targetType, String targetId);

    List<AuditLog> findByCorrelationIdOrderByIdAsc(String correlationId);
}
