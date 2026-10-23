package com.darshan.ledger.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.audit.AuditEvent;
import com.darshan.ledger.audit.AuditLogWriter;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.HoldService;
import com.darshan.ledger.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class PendingTransferExpiryJobTests {

    private static final Instant NOW = Instant.parse("2026-10-23T10:15:30Z");
    private static final Currency INR = Currency.getInstance("INR");

    @Mock
    private HoldService holdService;

    @Mock
    private PendingTransferExpirer expirer;

    @Mock
    private AuditLogWriter auditLogWriter;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void oneTransferThatCannotBeLockedDoesNotStopTheOthersAndOnlyRealExpiriesAreAudited() {
        PendingTransferExpiryJob job = new PendingTransferExpiryJob(holdService, expirer, auditLogWriter, clock);
        given(holdService.transferIdsOfHoldsExpiredAt(NOW, 100)).willReturn(List.of(1L, 2L, 3L));
        given(expirer.expire(1L, NOW)).willThrow(new CannotAcquireLockException("a checker holds the row"));
        given(expirer.expire(2L, NOW)).willReturn(Optional.of(new ExpiredTransfer(2L, "TRF-0000000002", 9L,
                Money.of(new BigDecimal("250000.00"), INR))));
        given(expirer.expire(3L, NOW)).willReturn(Optional.empty());

        job.run();

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogWriter).write(event.capture());
        assertThat(event.getValue().actor()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(event.getValue().action()).isEqualTo("TRANSFER_EXPIRE");
        assertThat(event.getValue().targetType()).isEqualTo("TRANSFER");
        assertThat(event.getValue().targetId()).isEqualTo("2");
        assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.getValue().details()).containsEntry("transferReference", "TRF-0000000002")
                .containsEntry("holdId", 9L)
                .containsEntry("amount", new BigDecimal("250000.00"))
                .containsEntry("currency", "INR");
    }

    @Test
    void aFullBatchInWhichNothingCouldBeExpiredEndsTheSweepInsteadOfSpinning() {
        PendingTransferExpiryJob job = new PendingTransferExpiryJob(holdService, expirer, auditLogWriter, clock);
        given(holdService.transferIdsOfHoldsExpiredAt(NOW, 100))
                .willReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        given(expirer.expire(anyLong(), any())).willReturn(Optional.empty());

        assertThat(job.expireDue(NOW)).isZero();

        verify(holdService).transferIdsOfHoldsExpiredAt(NOW, 100);
        verify(auditLogWriter, never()).write(any());
    }
}
