package com.darshan.ledger.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// The scheduled entry point must leave a trail for the night whatever happens, and must never let
// an exception escape to the scheduler thread, which would log it once and run nothing else.
@ExtendWith(MockitoExtension.class)
class InterestAccrualJobTests {

    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 31);

    @Mock
    private InterestAccrualService service;

    @Mock
    private AuditLogWriter auditLogWriter;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC);

    @Test
    void aSuccessfulRunAccruesYesterdayAndAuditsTheCountsAsTheSystem() {
        given(service.accrue(YESTERDAY)).willReturn(new InterestAccrualResult(YESTERDAY, 12, 10, 2, true, 9, 1, 0));

        new InterestAccrualJob(service, auditLogWriter, clock).run();

        AuditEvent event = writtenEvent();
        assertThat(event.actor()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(event.roles()).isEmpty();
        assertThat(event.action()).isEqualTo("INTEREST_ACCRUAL");
        assertThat(event.targetType()).isEqualTo("ACCRUAL_DATE");
        assertThat(event.targetId()).isEqualTo("2026-10-31");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.errorCode()).isNull();
        assertThat(event.clientIp()).isNull();
        assertThat(event.details()).containsExactlyInAnyOrderEntriesOf(Map.of("accountsProcessed", 12, "accrued", 10,
                "alreadyAccrued", 2, "monthEnd", true, "posted", 9, "carriedForward", 1, "postingFailures", 0));
    }

    @Test
    void aFailedRunIsAuditedWithItsErrorCodeAndDoesNotEscapeToTheScheduler() {
        given(service.accrue(YESTERDAY)).willThrow(new BusinessRuleException(InterestErrorCode.ACCRUAL_DATE_NOT_ENDED,
                "The day has not ended"));

        assertThatCode(() -> new InterestAccrualJob(service, auditLogWriter, clock).run()).doesNotThrowAnyException();

        AuditEvent event = writtenEvent();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.errorCode()).isEqualTo("ACCRUAL_DATE_NOT_ENDED");
        assertThat(event.targetId()).isEqualTo("2026-10-31");
        assertThat(event.details()).isEmpty();
    }

    private AuditEvent writtenEvent() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogWriter).write(event.capture());
        return event.getValue();
    }
}
