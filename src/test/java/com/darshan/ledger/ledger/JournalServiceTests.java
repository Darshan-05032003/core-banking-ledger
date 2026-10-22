package com.darshan.ledger.ledger;

import static com.darshan.ledger.ledger.LedgerFixtures.INR;
import static com.darshan.ledger.ledger.LedgerFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.money.Money;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JournalServiceTests {

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private PostingService postingService;

    private JournalService service;
    private Account account;
    private GlAccount vault;
    private JournalEntry deposit;

    @BeforeEach
    void setUp() {
        service = new JournalService(journalEntryRepository, postingService, new JournalMapperImpl());
        account = LedgerFixtures.account(2L, INR);
        vault = LedgerFixtures.cashVault();
        deposit = journal(40L, "JRN-0000000040", JournalType.DEPOSIT, null);
        Money amount = Money.of("500.00", INR);
        deposit.addEntry(new AccountRef.GlAccountRef(vault), EntryDirection.DEBIT, amount);
        deposit.addEntry(new AccountRef.CustomerAccountRef(account), EntryDirection.CREDIT, amount);
    }

    @Test
    void getMapsTheJournalWithMaskedAccountNumbers() {
        given(journalEntryRepository.findById(40L)).willReturn(Optional.of(deposit));

        JournalResponse response = service.get(40L);

        assertThat(response.reference()).isEqualTo("JRN-0000000040");
        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).glAccount()).isEqualTo(GlAccountCode.CASH_VAULT);
        assertThat(response.entries().get(0).accountId()).isNull();
        assertThat(response.entries().get(1).accountId()).isEqualTo(2L);
        assertThat(response.entries().get(1).maskedAccountNumber()).isEqualTo(account.getAccountNumber().masked());
        assertThat(response.entries().get(1).amount()).isEqualByComparingTo("500.00");
        assertThat(response.entries().get(1).currency()).isEqualTo("INR");
    }

    @Test
    void reversalPostsEveryLegFlippedAndLinkedToTheOriginal() {
        given(journalEntryRepository.findById(40L)).willReturn(Optional.of(deposit));
        given(journalEntryRepository.existsByReversalOfId(40L)).willReturn(false);
        JournalEntry reversal = journal(41L, "JRN-0000000041", JournalType.REVERSAL, 40L);
        given(postingService.post(any())).willReturn(reversal);

        JournalResponse response = service.reverse(40L, new ReversalRequest("Teller keyed the wrong account"));

        ArgumentCaptor<Posting> posting = ArgumentCaptor.forClass(Posting.class);
        verify(postingService).post(posting.capture());
        assertThat(posting.getValue().type()).isEqualTo(JournalType.REVERSAL);
        assertThat(posting.getValue().reversalOfId()).isEqualTo(40L);
        assertThat(posting.getValue().description())
                .isEqualTo("Reversal of JRN-0000000040: Teller keyed the wrong account");
        assertThat(posting.getValue().legs()).hasSize(2);
        assertThat(posting.getValue().legs().get(0).account()).isEqualTo(new AccountRef.GlAccountRef(vault));
        assertThat(posting.getValue().legs().get(0).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(posting.getValue().legs().get(1).account()).isEqualTo(new AccountRef.CustomerAccountRef(account));
        assertThat(posting.getValue().legs().get(1).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(posting.getValue().legs().get(1).amount()).isEqualTo(Money.of("500.00", INR));
        assertThat(response.id()).isEqualTo(41L);
        assertThat(response.reversalOfId()).isEqualTo(40L);
    }

    @Test
    void aJournalCanBeReversedOnlyOnce() {
        given(journalEntryRepository.findById(40L)).willReturn(Optional.of(deposit));
        given(journalEntryRepository.existsByReversalOfId(40L)).willReturn(true);

        assertThatThrownBy(() -> service.reverse(40L, new ReversalRequest("Again")))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.JOURNAL_ALREADY_REVERSED);
        verify(postingService, never()).post(any());
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        JournalEntry reversal = journal(41L, "JRN-0000000041", JournalType.REVERSAL, 40L);
        given(journalEntryRepository.findById(41L)).willReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.reverse(41L, new ReversalRequest("Undo the undo")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(LedgerErrorCode.JOURNAL_NOT_REVERSIBLE);
        verify(postingService, never()).post(any());
    }

    @Test
    void unknownJournalsAre404() {
        given(journalEntryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reverse(99L, new ReversalRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static JournalEntry journal(long id, String reference, JournalType type, Long reversalOfId) {
        JournalEntry journal = new JournalEntry(reference, type, "Cash", NOW, "teller1", reversalOfId);
        ReflectionTestUtils.setField(journal, "id", id);
        return journal;
    }
}
