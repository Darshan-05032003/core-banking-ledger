package com.darshan.ledger.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.Hold;
import com.darshan.ledger.ledger.HoldService;
import com.darshan.ledger.ledger.LockTimeout;
import com.darshan.ledger.ledger.PostingService;
import com.darshan.ledger.security.CurrentUser;
import com.darshan.ledger.transfer.Transfer;
import com.darshan.ledger.transfer.TransferRepository;
import com.darshan.ledger.transfer.TransferStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PendingTransferExpirerTests {

    private static final Instant NOW = Instant.parse("2026-10-23T10:15:30Z");
    private static final Currency INR = Currency.getInstance("INR");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private HoldService holdService;

    @Mock
    private PostingService postingService;

    @Mock
    private LockTimeout lockTimeout;

    private final Account sender = account(1L);
    private final Account beneficiary = account(2L);

    @Test
    void aTransferDecidedInTheMeantimeIsLeftAlone() {
        Transfer decided = pending();
        decided.reject("checker1", "Suspicious", NOW.minusSeconds(5));
        given(transferRepository.lockById(7L)).willReturn(Optional.of(decided));

        assertThat(expirer().expire(7L, NOW)).isEmpty();

        verify(lockTimeout).applyToCurrentTransaction();
        verify(holdService, never()).release(any(), any(), any());
    }

    @Test
    void aHoldThatHasNotLapsedYetIsNotExpired() {
        Transfer transfer = pending();
        Hold hold = mock(Hold.class);
        given(transferRepository.lockById(7L)).willReturn(Optional.of(transfer));
        given(holdService.findActive(7L)).willReturn(Optional.of(hold));
        given(hold.isExpiredAt(NOW)).willReturn(false);

        assertThat(expirer().expire(7L, NOW)).isEmpty();

        assertThat(transfer.isPendingApproval()).isTrue();
        verify(postingService, never()).lockBalances(any());
    }

    @Test
    void aPendingTransferWithoutAnActiveHoldIsACorruptedStateNotASilentSkip() {
        given(transferRepository.lockById(7L)).willReturn(Optional.of(pending()));
        given(holdService.findActive(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> expirer().expire(7L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no active hold");
    }

    @Test
    void aLapsedHoldIsReleasedUnderTheSenderLockAndTheTransferExpiresAsTheSystem() {
        Transfer transfer = pending();
        Hold hold = mock(Hold.class);
        AccountBalance senderBalance = mock(AccountBalance.class);
        given(transferRepository.lockById(7L)).willReturn(Optional.of(transfer));
        given(holdService.findActive(7L)).willReturn(Optional.of(hold));
        given(hold.isExpiredAt(NOW)).willReturn(true);
        given(hold.getId()).willReturn(9L);
        given(hold.getAmount()).willReturn(Money.of("250000.00", INR));
        given(postingService.lockBalances(List.of(sender))).willReturn(Map.of(1L, senderBalance));

        Optional<ExpiredTransfer> expired = expirer().expire(7L, NOW);

        verify(holdService).release(hold, senderBalance, NOW);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.EXPIRED);
        assertThat(transfer.getDecidedBy()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(expired).contains(new ExpiredTransfer(7L, "TRF-0000000007", 9L, Money.of("250000.00", INR)));
    }

    private PendingTransferExpirer expirer() {
        return new PendingTransferExpirer(transferRepository, holdService, postingService, lockTimeout);
    }

    private Transfer pending() {
        Transfer transfer = Transfer.pendingApproval("TRF-0000000007", sender, beneficiary,
                Money.of("250000.00", INR), "Car", "asha", NOW.minusSeconds(60));
        ReflectionTestUtils.setField(transfer, "id", 7L);
        return transfer;
    }

    private static Account account(long id) {
        Customer customer = new Customer("Asha Rao", "asha" + id + "@example.com", "+91980000" + "%04d".formatted(id),
                LocalDate.of(1990, 5, 17), NOW);
        ReflectionTestUtils.setField(customer, "id", id);
        Account account = new Account(AccountNumber.of("0001", id), customer, AccountType.SAVINGS, INR,
                LocalDate.of(2026, 10, 22), NOW);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
