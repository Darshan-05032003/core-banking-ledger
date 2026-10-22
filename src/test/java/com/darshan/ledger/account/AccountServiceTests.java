package com.darshan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.customer.CustomerService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final AccountNumber NUMBER = AccountNumber.of("0001", 1);

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CustomerService customerService;
    @Mock
    private AccountNumberGenerator accountNumberGenerator;
    @Mock
    private AccountBalances accountBalances;

    private AccountService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountRepository, customerService, accountNumberGenerator, accountBalances,
                new AccountMapperImpl(), new AccountProperties("0001", "INR"), Clock.fixed(NOW, ZoneOffset.UTC));
        customer = new Customer("Asha Rao", "asha@example.com", "+919876543210", LocalDate.of(1990, 5, 17), NOW);
    }

    @Test
    void opensAnActiveAccountForAVerifiedCustomerUsingTheClockAndDefaultCurrency() {
        customer.verifyKyc(NOW);
        given(customerService.requireCustomer(1L)).willReturn(customer);
        given(accountNumberGenerator.next()).willReturn(NUMBER);
        given(accountRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = service.open(new OpenAccountRequest(1L, AccountType.SAVINGS, null));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrency()).isEqualTo(INR);
        assertThat(saved.getValue().getOpenedOn()).isEqualTo(TODAY);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.maskedAccountNumber()).isEqualTo(NUMBER.masked());
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.type()).isEqualTo(AccountType.SAVINGS);
        verify(accountBalances).initialize(saved.getValue());
    }

    @Test
    void balanceCombinesTheAccountWithItsLedgerSnapshot() {
        Account account = activeAccount();
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(accountBalances.snapshot(account))
                .willReturn(new AccountBalanceSnapshot(Money.of("250.00", INR), Money.of("200.00", INR)));

        AccountBalanceResponse response = service.balance(10L);

        assertThat(response.maskedAccountNumber()).isEqualTo(NUMBER.masked());
        assertThat(response.ledgerBalance()).isEqualByComparingTo("250.00");
        assertThat(response.availableBalance()).isEqualByComparingTo("200.00");
        assertThat(response.onHold()).isEqualByComparingTo("50.00");
        assertThat(response.currency()).isEqualTo("INR");
    }

    @Test
    void refusesToOpenAnAccountBeforeKycIsVerified() {
        given(customerService.requireCustomer(1L)).willReturn(customer);

        assertThatThrownBy(() -> service.open(new OpenAccountRequest(1L, AccountType.SAVINGS, "INR")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.CUSTOMER_NOT_VERIFIED);
        verify(accountNumberGenerator, never()).next();
        verify(accountRepository, never()).save(any());
        verify(accountBalances, never()).initialize(any());
    }

    @Test
    void refusesAnUnknownCurrencyCode() {
        customer.verifyKyc(NOW);
        given(customerService.requireCustomer(1L)).willReturn(customer);

        assertThatThrownBy(() -> service.open(new OpenAccountRequest(1L, AccountType.CURRENT, "XYZ")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.UNSUPPORTED_CURRENCY);
    }

    @Test
    void closesAnAccountWithZeroBalanceAndNoHolds() {
        Account account = activeAccount();
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(accountBalances.snapshotForUpdate(account)).willReturn(AccountBalanceSnapshot.empty(INR));

        AccountResponse response = service.close(10L);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedOn()).isEqualTo(TODAY);
        assertThat(response.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(response.closedOn()).isEqualTo(TODAY);
        verify(accountBalances, never()).snapshot(any());
    }

    @Test
    void refusesToCloseWhileMoneyIsOnHold() {
        Account account = activeAccount();
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(accountBalances.snapshotForUpdate(account))
                .willReturn(new AccountBalanceSnapshot(Money.of("250.00", INR), Money.of("200.00", INR)));

        assertThatThrownBy(() -> service.close(10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("50.00 INR on hold")
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.FUNDS_ON_HOLD);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void refusesToCloseWithANonZeroBalance() {
        Account account = activeAccount();
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(accountBalances.snapshotForUpdate(account))
                .willReturn(new AccountBalanceSnapshot(Money.of("0.01", INR), Money.of("0.01", INR)));

        assertThatThrownBy(() -> service.close(10L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.BALANCE_NOT_ZERO);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void freezeAndUnfreezeFollowTheStateMachine() {
        Account account = activeAccount();
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));

        assertThat(service.freeze(10L).status()).isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(() -> service.freeze(10L)).isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_NOT_ACTIVE);
        assertThat(service.unfreeze(10L).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThatThrownBy(() -> service.unfreeze(10L)).isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_NOT_FROZEN);
    }

    @Test
    void closedAccountsCannotChangeStateAnyMore() {
        Account account = activeAccount();
        account.close(TODAY);
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(accountBalances.snapshotForUpdate(account)).willReturn(AccountBalanceSnapshot.empty(INR));

        assertThatThrownBy(() -> service.freeze(10L)).isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_CLOSED);
        assertThatThrownBy(() -> service.unfreeze(10L)).isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_CLOSED);
        assertThatThrownBy(() -> service.close(10L)).isInstanceOf(ConflictException.class)
                .extracting(ex -> ((LedgerException) ex).getErrorCode()).isEqualTo(AccountErrorCode.ACCOUNT_CLOSED);
    }

    private Account activeAccount() {
        customer.verifyKyc(NOW);
        return new Account(NUMBER, customer, AccountType.SAVINGS, INR, TODAY, NOW);
    }
}
