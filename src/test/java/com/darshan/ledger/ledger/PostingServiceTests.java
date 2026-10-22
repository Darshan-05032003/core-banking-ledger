package com.darshan.ledger.ledger;

import static com.darshan.ledger.ledger.LedgerFixtures.INR;
import static com.darshan.ledger.ledger.LedgerFixtures.NOW;
import static com.darshan.ledger.ledger.LedgerFixtures.TODAY;
import static com.darshan.ledger.ledger.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.security.CurrentUser;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PostingServiceTests {

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private AccountBalanceRepository accountBalanceRepository;
    @Mock
    private JournalReferenceGenerator referenceGenerator;
    @Mock
    private CurrentUser currentUser;
    @Mock
    private EntityManager entityManager;
    @Mock
    private LockTimeout lockTimeout;
    @Mock
    private ApplicationEventPublisher events;

    private PostingService service;
    private GlAccount vault;
    private Account first;
    private Account second;
    private AccountBalance firstBalance;
    private AccountBalance secondBalance;

    @BeforeEach
    void setUp() {
        service = new PostingService(journalEntryRepository, accountBalanceRepository, referenceGenerator, currentUser,
                Clock.fixed(NOW, ZoneOffset.UTC), entityManager, lockTimeout, events);
        vault = LedgerFixtures.cashVault();
        first = LedgerFixtures.account(2L, INR);
        second = LedgerFixtures.account(5L, INR);
        firstBalance = new AccountBalance(first);
        secondBalance = new AccountBalance(second);
    }

    @Test
    void postsABalancedJournalAndUpdatesTheLockedBalance() {
        given(accountBalanceRepository.lockByAccountId(2L)).willReturn(Optional.of(firstBalance));
        given(referenceGenerator.next()).willReturn("JRN-0000000001");
        given(currentUser.name()).willReturn("teller1");
        Money amount = Money.of("500.00", INR);

        JournalEntry journal = service.post(Posting.of(JournalType.DEPOSIT, "Cash deposit",
                List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(first, amount))));

        verify(journalEntryRepository).save(journal);
        verify(events).publishEvent(new JournalPosted(journal));
        assertThat(journal.getReference()).isEqualTo("JRN-0000000001");
        assertThat(journal.getType()).isEqualTo(JournalType.DEPOSIT);
        assertThat(journal.getPostedAt()).isEqualTo(NOW);
        assertThat(journal.getCreatedBy()).isEqualTo("teller1");
        assertThat(journal.getReversalOfId()).isNull();
        assertThat(journal.getEntries()).hasSize(2);
        assertThat(journal.getEntries().get(0).getGlAccount()).isEqualTo(vault);
        assertThat(journal.getEntries().get(0).getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(journal.getEntries().get(1).getAccount()).isEqualTo(first);
        assertThat(journal.getEntries().get(1).getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(journal.getEntries().get(1).getAmount()).isEqualTo(amount);
        assertThat(firstBalance.ledgerBalance()).isEqualTo(amount);
        assertThat(firstBalance.availableBalance()).isEqualTo(amount);
    }

    @Test
    void rejectsAJournalWhoseDebitsDoNotEqualItsCredits() {
        Posting posting = Posting.of(JournalType.DEPOSIT, "Broken",
                List.of(PostingLeg.debit(vault, Money.of("500.00", INR)), PostingLeg.credit(first, Money.of("400.00", INR))));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Debits 500.00 INR do not equal credits 400.00 INR")
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.UNBALANCED_JOURNAL);
        verifyNoInteractions(journalEntryRepository, accountBalanceRepository, referenceGenerator);
    }

    @Test
    void rejectsLegsInDifferentCurrenciesEvenWhenEachSideAddsUp() {
        Posting posting = Posting.of(JournalType.DEPOSIT, "Mixed", List.of(
                PostingLeg.debit(vault, Money.of("100.00", INR)), PostingLeg.credit(first, Money.of("100.00", INR)),
                PostingLeg.debit(vault, Money.of("1.00", USD)), PostingLeg.credit(first, Money.of("1.00", USD))));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.MIXED_CURRENCY_JOURNAL);
        verifyNoInteractions(journalEntryRepository, accountBalanceRepository);
    }

    @Test
    void rejectsALegWhoseCurrencyIsNotTheAccountCurrency() {
        Account dollarAccount = LedgerFixtures.account(7L, USD);
        Money amount = Money.of("100.00", INR);
        Posting posting = Posting.of(JournalType.DEPOSIT, "Wrong currency",
                List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(dollarAccount, amount)));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.CURRENCY_MISMATCH);
        verify(accountBalanceRepository, never()).lockByAccountId(anyLong());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void refusesToMoveMoneyOnAClosedAccount() {
        first.close(TODAY);
        Money amount = Money.of("10.00", INR);
        Posting posting = Posting.of(JournalType.DEPOSIT, "Late deposit",
                List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(first, amount)));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_CLOSED);
        verifyNoInteractions(journalEntryRepository, accountBalanceRepository);
    }

    @Test
    void refusesADebitBeyondTheAvailableBalanceWithoutWritingAnything() {
        firstBalance.credit(Money.of("50.00", INR));
        given(accountBalanceRepository.lockByAccountId(2L)).willReturn(Optional.of(firstBalance));
        Money amount = Money.of("100.00", INR);
        Posting posting = Posting.of(JournalType.WITHDRAWAL, "Cash withdrawal",
                List.of(PostingLeg.debit(first, amount), PostingLeg.credit(vault, amount)));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
        verify(journalEntryRepository, never()).save(any());
        assertThat(firstBalance.availableBalance()).isEqualTo(Money.of("50.00", INR));
    }

    @Test
    void locksBalancesInAscendingAccountIdOrderRegardlessOfLegOrder() {
        secondBalance.credit(Money.of("100.00", INR));
        given(accountBalanceRepository.lockByAccountId(2L)).willReturn(Optional.of(firstBalance));
        given(accountBalanceRepository.lockByAccountId(5L)).willReturn(Optional.of(secondBalance));
        given(referenceGenerator.next()).willReturn("JRN-0000000002");
        given(currentUser.name()).willReturn("teller1");
        Money amount = Money.of("100.00", INR);

        service.post(Posting.of(JournalType.WITHDRAWAL, "Move",
                List.of(PostingLeg.debit(second, amount), PostingLeg.credit(first, amount))));

        InOrder locks = inOrder(lockTimeout, accountBalanceRepository, entityManager);
        locks.verify(lockTimeout).applyToCurrentTransaction();
        locks.verify(accountBalanceRepository).lockByAccountId(2L);
        locks.verify(entityManager).refresh(first);
        locks.verify(accountBalanceRepository).lockByAccountId(5L);
        locks.verify(entityManager).refresh(second);
        assertThat(secondBalance.ledgerBalance().isZero()).isTrue();
        assertThat(firstBalance.ledgerBalance()).isEqualTo(amount);
    }

    @Test
    void refusesAnAccountThatWasClosedWhileWaitingForItsLock() {
        given(accountBalanceRepository.lockByAccountId(2L)).willReturn(Optional.of(firstBalance));
        willAnswer(invocation -> {
            first.close(TODAY);
            return null;
        }).given(entityManager).refresh(first);
        Money amount = Money.of("10.00", INR);
        Posting posting = Posting.of(JournalType.DEPOSIT, "Raced with a closure",
                List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(first, amount)));

        assertThatThrownBy(() -> service.post(posting))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_CLOSED);
        verify(journalEntryRepository, never()).save(any());
        assertThat(firstBalance.ledgerBalance().isZero()).isTrue();
    }

    @Test
    void postingShapesAreValidatedUpFront() {
        Money amount = Money.of("10.00", INR);
        List<PostingLeg> single = List.of(PostingLeg.debit(vault, amount));
        List<PostingLeg> pair = List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(first, amount));

        assertThatThrownBy(() -> PostingLeg.debit(vault, Money.zero(INR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Posting.of(JournalType.DEPOSIT, "One leg", single))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posting(JournalType.DEPOSIT, "Not a reversal", pair, 4L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posting(JournalType.REVERSAL, "Reversal without original", pair, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PostingLeg.debit(first, amount).opposite().direction()).isEqualTo(EntryDirection.CREDIT);
    }
}
