package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.ledger.AccountBalance;
import com.darshan.ledger.ledger.LedgerFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.springframework.test.util.ReflectionTestUtils;

// Entities with database-style ids for rule and service tests that never touch a database.
final class TransferFixtures {

    static final Currency INR = Currency.getInstance("INR");
    static final Currency USD = Currency.getInstance("USD");
    static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    static final TransferLimitsProperties LIMITS =
            new TransferLimitsProperties(new BigDecimal("500000.00"), new BigDecimal("2000000.00"));

    private TransferFixtures() {
    }

    static Account verifiedAccount(long id, Currency currency) {
        Customer customer = new Customer("Asha Rao", "asha" + id + "@example.com", "+91980000" + "%04d".formatted(id),
                LocalDate.of(1990, 5, 17), NOW);
        ReflectionTestUtils.setField(customer, "id", id);
        customer.verifyKyc(NOW);
        return account(id, customer, currency);
    }

    static Account unverifiedAccount(long id, Currency currency) {
        Customer customer = new Customer("Ravi Rao", "ravi" + id + "@example.com", "+91970000" + "%04d".formatted(id),
                LocalDate.of(1990, 5, 17), NOW);
        ReflectionTestUtils.setField(customer, "id", id);
        return account(id, customer, currency);
    }

    static AccountBalance balance(Account account, String available) {
        return LedgerFixtures.balance(account, available);
    }

    static TransferRequest request(Account from, Account to, String amount, String currency) {
        return new TransferRequest(from.getAccountNumber().value(), to.getAccountNumber().value(),
                new BigDecimal(amount), currency, "Rent");
    }

    static TransferContext context(Account from, Account to, String amount, String currency, String available) {
        return new TransferContext(request(from, to, amount, currency),
                Money.of(amount, Currency.getInstance(currency)), from, to, balance(from, available));
    }

    static TransferContext context(Account from, Account to, String amount) {
        return context(from, to, amount, "INR", "1000000.00");
    }

    private static Account account(long id, Customer customer, Currency currency) {
        Account account = new Account(AccountNumber.of("0001", id), customer, AccountType.SAVINGS, currency, TODAY, NOW);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
