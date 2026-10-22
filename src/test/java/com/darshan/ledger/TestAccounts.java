package com.darshan.ledger;

import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.account.OpenAccountRequest;
import com.darshan.ledger.customer.CustomerService;
import com.darshan.ledger.customer.OnboardCustomerRequest;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;

// Shortcut for tests that need a funded-ready account and are not about onboarding itself.
@TestComponent
public class TestAccounts {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final CustomerService customerService;
    private final AccountService accountService;

    public TestAccounts(CustomerService customerService, AccountService accountService) {
        this.customerService = customerService;
        this.accountService = accountService;
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
}
