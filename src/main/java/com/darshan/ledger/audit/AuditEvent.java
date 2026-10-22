package com.darshan.ledger.audit;

import java.util.List;
import java.util.Map;

public record AuditEvent(String actor, List<String> roles, String action, String targetType, String targetId,
        AuditOutcome outcome, String errorCode, String clientIp, String correlationId, Map<String, Object> details) {
}
