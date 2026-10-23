package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class EndOfDayCheckJobTests {

    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 31);

    @Mock
    private EndOfDayCheck check;

    @Mock
    private AuditLogWriter auditLogWriter;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void aCleanNightIsFiledAsASuccessUnderTheBusinessDate() {
        given(check.run(YESTERDAY)).willReturn(result(0, 0, true));

        new EndOfDayCheckJob(check, auditLogWriter, clock).run();

        AuditEvent event = writtenEvent();
        assertThat(event.actor()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(event.action()).isEqualTo("EOD_CHECK");
        assertThat(event.targetType()).isEqualTo("BUSINESS_DATE");
        assertThat(event.targetId()).isEqualTo("2026-10-31");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.errorCode()).isNull();
        assertThat(event.details()).containsExactlyInAnyOrderEntriesOf(Map.of("accountsChecked", 40L,
                "balanceMismatches", 0L, "journalsChecked", 120L, "unbalancedJournals", 0L, "totalsBalanced", true));
    }

    @Test
    void aBreakIsFiledAsAFailureWithTheLedgerOutOfBalanceCode() {
        given(check.run(YESTERDAY)).willReturn(result(2, 1, false));

        new EndOfDayCheckJob(check, auditLogWriter, clock).run();

        AuditEvent event = writtenEvent();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.errorCode()).isEqualTo("LEDGER_OUT_OF_BALANCE");
        assertThat(event.details()).containsEntry("balanceMismatches", 2L).containsEntry("unbalancedJournals", 1L);
    }

    @Test
    void aCheckThatCouldNotRunIsFiledWithItsErrorCodeAndTheSchedulerThreadSurvives() {
        given(check.run(YESTERDAY)).willThrow(new CannotAcquireLockException("statement timeout"));

        assertThatCode(() -> new EndOfDayCheckJob(check, auditLogWriter, clock).run()).doesNotThrowAnyException();

        AuditEvent event = writtenEvent();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.errorCode()).isEqualTo("LOCK_TIMEOUT");
        assertThat(event.details()).isEmpty();
    }

    private static EndOfDayCheckResult result(long balanceMismatches, long unbalancedJournals, boolean passed) {
        CurrencyTotals totals = new CurrencyTotals("INR", new BigDecimal("500.00"),
                passed ? new BigDecimal("500.00") : new BigDecimal("499.00"));
        return new EndOfDayCheckResult(YESTERDAY, 40, balanceMismatches, List.of(), 120, unbalancedJournals, List.of(),
                List.of(totals), passed);
    }

    private AuditEvent writtenEvent() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogWriter).write(event.capture());
        return event.getValue();
    }
}
