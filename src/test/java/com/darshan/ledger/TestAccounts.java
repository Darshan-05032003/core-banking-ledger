package com.darshan.ledger;

import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.account.OpenAccountRequest;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.customer.CustomerService;
import com.darshan.ledger.customer.OnboardCustomerRequest;
import com.darshan.ledger.ledger.CashRequest;
import com.darshan.ledger.ledger.TellerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;

// Shortcut for tests that need a funded-ready account and are not about onboarding itself.
@TestComponent
public class TestAccounts {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final CustomerService customerService;
    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final TellerService tellerService;

    public TestAccounts(CustomerService customerService, AccountService accountService,
            AccountRepository accountRepository, TellerService tellerService) {
        this.customerService = customerService;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.tellerService = tellerService;
    }

    public long openActiveAccount() {
        return openActiveAccount("INR");
    }

    public long openActiveAccount(String currency) {
        int n = SEQUENCE.incrementAndGet();
        long customerId = customerService.onboard(new OnboardCustomerRequest(
                "Fixture Customer " + n, "fixture" + n + "@example.com", "+919800000" + "%03d".formatted(n % 1000),
                LocalDate.of(1990, 1, 1))).id();
        customerService.verifyKyc(customerId);
        return accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, currency)).id();
    }

    public long openFundedAccount(String amount) {
        long accountId = openActiveAccount();
        fund(accountId, amount);
        return accountId;
    }

    public void fund(long accountId, String amount) {
        String currency = accountRepository.findById(accountId).orElseThrow().getCurrency().getCurrencyCode();
        tellerService.deposit(accountId, new CashRequest(new BigDecimal(amount), currency, "Test funding"));
    }

    public String accountNumber(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getAccountNumber().value();
    }

    public long customerIdOf(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCustomer().getId();
    }
}
