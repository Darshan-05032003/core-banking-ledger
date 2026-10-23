package com.darshan.ledger.ledger;

import static com.darshan.ledger.ledger.LedgerFixtures.INR;
import static com.darshan.ledger.ledger.LedgerFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class HoldServiceTests {

    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(24));

    private final HoldRepository repository = mock(HoldRepository.class);
    private final HoldService service = new HoldService(repository);
    private final Account account = LedgerFixtures.account(5L, INR);
    private final AccountBalance balance = LedgerFixtures.balance(account, "1000.00");

    @BeforeEach
    void saveReturnsTheHold() {
        given(repository.save(any(Hold.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void placingAHoldReservesTheMoneyWithoutMovingIt() {
        Hold hold = service.place(account, balance, 42L, Money.of("250.00", INR), NOW, EXPIRES_AT);

        assertThat(hold.isActive()).isTrue();
        assertThat(hold.getTransferId()).isEqualTo(42L);
        assertThat(hold.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(balance.ledgerBalance()).isEqualTo(Money.of("1000.00", INR));
        assertThat(balance.availableBalance()).isEqualTo(Money.of("750.00", INR));
    }

    @Test
    void moreThanTheAvailableBalanceCannotBeReserved() {
        assertThatThrownBy(() -> service.place(account, balance, 42L, Money.of("1000.01", INR), NOW, EXPIRES_AT))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
        assertThat(balance.availableBalance()).isEqualTo(Money.of("1000.00", INR));
    }

    @Test
    void aHoldIsRefusedOnABalanceRowThatBelongsToAnotherAccount() {
        Account other = LedgerFixtures.account(6L, INR);

        assertThatThrownBy(() -> service.place(other, balance, 42L, Money.of("1.00", INR), NOW, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to account 6");
    }

    @Test
    void capturingHandsTheReservationBackForThePostingThatFollows() {
        Hold hold = service.place(account, balance, 42L, Money.of("250.00", INR), NOW, EXPIRES_AT);

        service.capture(hold, balance, NOW.plusSeconds(60));

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(hold.getReleasedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(balance.availableBalance()).isEqualTo(Money.of("1000.00", INR));
    }

    @Test
    void releasingEndsTheHoldAndRestoresTheAvailableBalance() {
        Hold hold = service.place(account, balance, 42L, Money.of("250.00", INR), NOW, EXPIRES_AT);

        service.release(hold, balance, NOW.plusSeconds(60));

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.isActive()).isFalse();
        assertThat(balance.availableBalance()).isEqualTo(Money.of("1000.00", INR));
    }

    @Test
    void aHoldEndsOnlyOnce() {
        Hold hold = service.place(account, balance, 42L, Money.of("250.00", INR), NOW, EXPIRES_AT);
        service.release(hold, balance, NOW);

        assertThatThrownBy(() -> service.capture(hold, balance, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already RELEASED");
        assertThat(balance.availableBalance()).isEqualTo(Money.of("1000.00", INR));
    }

    @Test
    void theReadsTheApprovalWorkflowNeedsGoThroughTheServiceWithABoundedBatch() {
        given(repository.findByTransferIdAndStatus(42L, HoldStatus.ACTIVE)).willReturn(Optional.empty());
        given(repository.findTransferIdsOfHoldsExpiredAt(NOW, PageRequest.of(0, 100))).willReturn(List.of(7L));

        assertThat(service.findActive(42L)).isEmpty();
        assertThat(service.transferIdsOfHoldsExpiredAt(NOW, 100)).containsExactly(7L);
        verify(repository).findTransferIdsOfHoldsExpiredAt(NOW, PageRequest.of(0, 100));
    }
}
