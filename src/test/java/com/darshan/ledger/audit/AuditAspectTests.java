package com.darshan.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.web.CorrelationIdFilter;
import com.darshan.ledger.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;

// The aspect proxied the way Spring does it in the application, without a context: what it writes
// for a success, for a failure, and what happens to the caller when the trail itself cannot be
// written.
class AuditAspectTests {

    private final AuditLogWriter writer = mock(AuditLogWriter.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private AuditedOperations operations;

    @BeforeEach
    void proxyTheTarget() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new AuditedOperations());
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditAspect(writer, currentUser));
        operations = factory.getProxy();
        given(currentUser.name()).willReturn("teller7");
        given(currentUser.roles()).willReturn(List.of("TELLER"));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aSuccessfulCallIsRecordedWithTheArgumentsTheResultAndTheCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "req-42");

        ResponseEntity<String> response = operations.freeze(42L, "court order");

        assertThat(response.getBody()).isEqualTo("frozen 42");
        AuditEvent event = written();
        assertThat(event.actor()).isEqualTo("teller7");
        assertThat(event.roles()).containsExactly("TELLER");
        assertThat(event.action()).isEqualTo("ACCOUNT_FREEZE");
        assertThat(event.targetType()).isEqualTo("ACCOUNT");
        assertThat(event.targetId()).isEqualTo("42");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.errorCode()).isNull();
        assertThat(event.clientIp()).as("no servlet request is bound to this thread").isNull();
        assertThat(event.correlationId()).isEqualTo("req-42");
        assertThat(event.details()).isEqualTo(Map.of("reason", "court order", "body", "frozen 42"));
    }

    @Test
    void aFailedCallIsRecordedWithItsErrorCodeAndTheFailureStillReachesTheCaller() {
        assertThatThrownBy(() -> operations.lookup(99L)).isInstanceOf(ResourceNotFoundException.class);

        AuditEvent event = written();
        assertThat(event.action()).isEqualTo("ACCOUNT_LOOKUP");
        assertThat(event.targetId()).isNull();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(event.details()).isEmpty();
    }

    @Test
    void aTrailThatCannotBeWrittenNeverTurnsACompletedOperationIntoAnError() {
        given(writer.write(any())).willThrow(new IllegalStateException("audit_log is unavailable"));

        assertThat(operations.freeze(42L, "court order").getBody()).isEqualTo("frozen 42");
    }

    private AuditEvent written() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).write(event.capture());
        return event.getValue();
    }

    static class AuditedOperations {

        @Audited(action = "ACCOUNT_FREEZE", targetType = "ACCOUNT", targetId = "#accountId",
                details = "{reason: #reason, body: #result}")
        public ResponseEntity<String> freeze(Long accountId, String reason) {
            return ResponseEntity.ok("frozen " + accountId);
        }

        @Audited(action = "ACCOUNT_LOOKUP", targetType = "ACCOUNT")
        public String lookup(Long accountId) {
            throw new ResourceNotFoundException("Account", accountId);
        }
    }
}
