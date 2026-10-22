package com.darshan.ledger.transfer;

import static com.darshan.ledger.transfer.TransferFixtures.INR;
import static com.darshan.ledger.transfer.TransferFixtures.NOW;
import static com.darshan.ledger.transfer.TransferFixtures.balance;
import static com.darshan.ledger.transfer.TransferFixtures.verifiedAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.LedgerErrorCode;
import com.darshan.ledger.ledger.Posting;
import com.darshan.ledger.ledger.PostingService;
import com.darshan.ledger.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransferProcessorTests {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PostingService postingService;
    @Mock
    private TransferRuleChain ruleChain;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransferReferenceGenerator referenceGenerator;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private CurrentUser currentUser;

    private TransferProcessor processor;
    private final Account sender = verifiedAccount(2L, INR);
    private final Account beneficiary = verifiedAccount(5L, INR);
    private final AccountBalance senderBalance = balance(sender, "1000.00");
    private final AccountBalance beneficiaryBalance = balance(beneficiary, "0.00");

    @BeforeEach
    void setUp() {
        processor = new TransferProcessor(accountRepository, postingService, ruleChain, transferRepository,
                referenceGenerator, new TransferMapperImpl(), idempotencyService, currentUser,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aTransferLocksBothBalancesRunsTheRulesPostsAndCompletesTheKeyInOneGo() {
        resolveBothAccounts();
        given(ruleChain.evaluate(any())).willReturn(TransferRuleResult.allowed());
        given(referenceGenerator.next()).willReturn("TRF-0000000003");
        given(currentUser.name()).willReturn("alice");
        JournalEntry journal = journal(40L, "JRN-0000000040");
        given(postingService.post(any())).willReturn(journal);
        given(transferRepository.save(any())).willAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            ReflectionTestUtils.setField(transfer, "id", 12L);
            return transfer;
        });

        TransferResponse response = processor.process(7L, request("250.00", "Rent"));

        ArgumentCaptor<TransferContext> context = ArgumentCaptor.forClass(TransferContext.class);
        verify(ruleChain).evaluate(context.capture());
        assertThat(context.getValue().sender()).isSameAs(sender);
        assertThat(context.getValue().beneficiary()).isSameAs(beneficiary);
        assertThat(context.getValue().senderBalance()).isSameAs(senderBalance);
        assertThat(context.getValue().amount()).isEqualTo(Money.of("250.00", INR));

        ArgumentCaptor<Posting> posting = ArgumentCaptor.forClass(Posting.class);
        verify(postingService).post(posting.capture());
        assertThat(posting.getValue().type()).isEqualTo(JournalType.TRANSFER);
        assertThat(posting.getValue().description()).isEqualTo("Transfer TRF-0000000003: Rent");
        assertThat(posting.getValue().legs()).hasSize(2);
        assertThat(posting.getValue().legs().get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(posting.getValue().legs().get(1).direction()).isEqualTo(EntryDirection.CREDIT);

        assertThat(response.id()).isEqualTo(12L);
        assertThat(response.reference()).isEqualTo("TRF-0000000003");
        assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.fromAccountId()).isEqualTo(2L);
        assertThat(response.toAccountId()).isEqualTo(5L);
        assertThat(response.maskedFromAccountNumber()).isEqualTo(sender.getAccountNumber().masked());
        assertThat(response.amount()).isEqualByComparingTo("250.00");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.journalId()).isEqualTo(40L);
        assertThat(response.journalReference()).isEqualTo("JRN-0000000040");
        assertThat(response.createdBy()).isEqualTo("alice");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.completedAt()).isEqualTo(NOW);
        assertThat(response.failureCode()).isNull();
        verify(idempotencyService).complete(7L, 201, response);
    }

    @Test
    void withoutRemarksTheJournalDescriptionIsJustTheReference() {
        resolveBothAccounts();
        given(ruleChain.evaluate(any())).willReturn(TransferRuleResult.allowed());
        given(referenceGenerator.next()).willReturn("TRF-0000000004");
        given(currentUser.name()).willReturn("alice");
        JournalEntry journal = journal(41L, "JRN-0000000041");
        given(postingService.post(any())).willReturn(journal);
        given(transferRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        processor.process(7L, request("1.00", null));

        ArgumentCaptor<Posting> posting = ArgumentCaptor.forClass(Posting.class);
        verify(postingService).post(posting.capture());
        assertThat(posting.getValue().description()).isEqualTo("Transfer TRF-0000000004");
    }

    @Test
    void aRuleRejectionCarriesTheResolvedPartiesAndPostsNothing() {
        resolveBothAccounts();
        given(ruleChain.evaluate(any())).willReturn(TransferRuleResult.rejected(LedgerErrorCode.INSUFFICIENT_FUNDS,
                "Available balance 1000.00 INR is lower than 5000.00 INR"));

        assertThatThrownBy(() -> processor.process(7L, request("5000.00", "Rent")))
                .isInstanceOf(TransferRejectedException.class)
                .satisfies(ex -> {
                    TransferRejectedException rejection = (TransferRejectedException) ex;
                    assertThat(rejection.getErrorCode()).isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
                    assertThat(rejection.getStatus().value()).isEqualTo(422);
                    assertThat(rejection.attempt()).contains(new TransferAttempt(2L, 5L, Money.of("5000.00", INR), "Rent"));
                    assertThat(rejection.extensions()).isEmpty();
                });
        verify(postingService, never()).post(any());
        verifyNoInteractions(transferRepository, referenceGenerator);
        verify(idempotencyService, never()).complete(anyLong(), anyInt(), any());
    }

    @Test
    void anUnknownAccountStillGoesThroughTheRulesButLeavesNoAttemptToRecord() {
        given(accountRepository.findByAccountNumber(sender.getAccountNumber())).willReturn(Optional.of(sender));
        given(accountRepository.findByAccountNumber(beneficiary.getAccountNumber())).willReturn(Optional.empty());
        given(postingService.lockBalances(List.of(sender))).willReturn(Map.of(2L, senderBalance));
        given(ruleChain.evaluate(any())).willReturn(TransferRuleResult.rejected(TransferErrorCode.ACCOUNT_NOT_FOUND,
                "Beneficiary account XXXXXXXX0057 does not exist"));

        assertThatThrownBy(() -> processor.process(7L, request("10.00", null)))
                .isInstanceOf(TransferRejectedException.class)
                .satisfies(ex -> assertThat(((TransferRejectedException) ex).attempt()).isEmpty());

        ArgumentCaptor<TransferContext> context = ArgumentCaptor.forClass(TransferContext.class);
        verify(ruleChain).evaluate(context.capture());
        assertThat(context.getValue().hasBeneficiary()).isFalse();
        assertThat(context.getValue().senderBalance()).isSameAs(senderBalance);
    }

    @Test
    void anAccountNumberWithABadCheckDigitIsRejectedBeforeAnyLookup() {
        String corrupted = flipLastDigit(beneficiary.getAccountNumber().value());

        assertThatThrownBy(() -> processor.process(7L, new TransferRequest(sender.getAccountNumber().value(),
                corrupted, new BigDecimal("10.00"), "INR", null)))
                .isInstanceOf(TransferRejectedException.class)
                .hasMessageContaining("XXXXXXXX" + corrupted.substring(8))
                .satisfies(ex -> assertThat(((TransferRejectedException) ex).getErrorCode())
                        .isEqualTo(TransferErrorCode.INVALID_ACCOUNT_NUMBER));
        verifyNoInteractions(accountRepository, postingService, ruleChain);
    }

    @Test
    void anUnknownCurrencyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> processor.process(7L, new TransferRequest(sender.getAccountNumber().value(),
                beneficiary.getAccountNumber().value(), new BigDecimal("10.00"), "XYZ", null)))
                .isInstanceOf(TransferRejectedException.class)
                .satisfies(ex -> assertThat(((TransferRejectedException) ex).getErrorCode())
                        .isEqualTo(AccountErrorCode.UNSUPPORTED_CURRENCY));
        verifyNoInteractions(accountRepository, postingService, ruleChain);
    }

    @Test
    void aRefusalFromThePostingServiceIsWrappedWithTheParties() {
        resolveBothAccounts();
        given(ruleChain.evaluate(any())).willReturn(TransferRuleResult.allowed());
        given(referenceGenerator.next()).willReturn("TRF-0000000005");
        given(postingService.post(any()))
                .willThrow(new BusinessRuleException(LedgerErrorCode.INSUFFICIENT_FUNDS, "raced"));

        assertThatThrownBy(() -> processor.process(7L, request("10.00", null)))
                .isInstanceOf(TransferRejectedException.class)
                .hasCauseInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    TransferRejectedException rejection = (TransferRejectedException) ex;
                    assertThat(rejection.getErrorCode()).isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
                    assertThat(rejection.attempt()).isPresent();
                });
        verify(transferRepository, never()).save(any());
        verify(idempotencyService, never()).complete(anyLong(), anyInt(), any());
    }

    private void resolveBothAccounts() {
        given(accountRepository.findByAccountNumber(sender.getAccountNumber())).willReturn(Optional.of(sender));
        given(accountRepository.findByAccountNumber(beneficiary.getAccountNumber())).willReturn(Optional.of(beneficiary));
        given(postingService.lockBalances(List.of(sender, beneficiary)))
                .willReturn(Map.of(2L, senderBalance, 5L, beneficiaryBalance));
    }

    private TransferRequest request(String amount, String remarks) {
        return new TransferRequest(sender.getAccountNumber().value(), beneficiary.getAccountNumber().value(),
                new BigDecimal(amount), "INR", remarks);
    }

    private static JournalEntry journal(long id, String reference) {
        JournalEntry journal = org.mockito.Mockito.mock(JournalEntry.class);
        given(journal.getId()).willReturn(id);
        given(journal.getReference()).willReturn(reference);
        return journal;
    }

    private static String flipLastDigit(String number) {
        char last = number.charAt(number.length() - 1);
        char flipped = last == '9' ? '0' : (char) (last + 1);
        String corrupted = number.substring(0, number.length() - 1) + flipped;
        assertThatThrownBy(() -> new AccountNumber(corrupted)).isInstanceOf(IllegalArgumentException.class);
        return corrupted;
    }
}
