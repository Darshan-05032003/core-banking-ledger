package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.customer.Customer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.springframework.test.util.ReflectionTestUtils;

// Entities with database-assigned ids, for unit tests that never touch a database. Public because
// the transfer rule tests need a funded balance and only this package may credit one.
public final class LedgerFixtures {

    static final Currency INR = Currency.getInstance("INR");
    static final Currency USD = Currency.getInstance("USD");
    static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private LedgerFixtures() {
    }

    static Account account(long id, Currency currency) {
        Customer customer = new Customer("Asha Rao", "asha@example.com", "+919876543210", LocalDate.of(1990, 5, 17), NOW);
        ReflectionTestUtils.setField(customer, "id", 1L);
        Account account = new Account(AccountNumber.of("0001", id), customer, AccountType.SAVINGS, currency, TODAY, NOW);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    static GlAccount glAccount(long id, GlAccountCode code, GlAccountType type) {
        GlAccount glAccount = new GlAccount();
        ReflectionTestUtils.setField(glAccount, "id", id);
        ReflectionTestUtils.setField(glAccount, "code", code);
        ReflectionTestUtils.setField(glAccount, "name", code.name());
        ReflectionTestUtils.setField(glAccount, "type", type);
        return glAccount;
    }

    static GlAccount cashVault() {
        return glAccount(1L, GlAccountCode.CASH_VAULT, GlAccountType.ASSET);
    }

    public static AccountBalance balance(Account account, String available) {
        AccountBalance balance = new AccountBalance(account);
        Money amount = Money.of(available, account.getCurrency());
        if (amount.isPositive()) {
            balance.credit(amount);
        }
        return balance;
    }
}
