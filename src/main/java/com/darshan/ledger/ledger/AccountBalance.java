package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountBalanceSnapshot;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.money.CurrencyMismatchException;
import com.darshan.ledger.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "account_balance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountBalance {

    @Id
    @Column(name = "account_id")
    @Getter
    private Long accountId;

    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal ledgerBalance;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    // Null until persisted, which is how Spring Data knows a balance with an assigned id is new.
    @Version
    private Long version;

    // Package-private on purpose, like every method below that changes a figure: only the ledger
    // package may create or move a balance, and the compiler enforces it before any test does.
    AccountBalance(Account account) {
        this.accountId = Objects.requireNonNull(account.getId(), "account must be persisted first");
        this.currency = account.getCurrency();
        this.ledgerBalance = Money.zero(currency).amount();
        this.availableBalance = Money.zero(currency).amount();
    }

    public Money ledgerBalance() {
        return Money.of(ledgerBalance, currency);
    }

    public Money availableBalance() {
        return Money.of(availableBalance, currency);
    }

    public AccountBalanceSnapshot snapshot() {
        return new AccountBalanceSnapshot(ledgerBalance(), availableBalance());
    }

    void credit(Money amount) {
        requireCurrency(amount);
        ledgerBalance = ledgerBalance().plus(amount).amount();
        availableBalance = availableBalance().plus(amount).amount();
    }

    // Debits are checked against the available balance, not the ledger balance, so money reserved
    // by a hold can never be spent twice.
    void debit(Money amount) {
        requireCurrency(amount);
        if (availableBalance().isLessThan(amount)) {
            throw new BusinessRuleException(LedgerErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance " + availableBalance() + " is lower than " + amount);
        }
        ledgerBalance = ledgerBalance().minus(amount).amount();
        availableBalance = availableBalance().minus(amount).amount();
    }

    // A hold reserves money without posting anything: the ledger balance stays, the available
    // balance drops, and the gap is exactly the sum of the account's active holds.
    void hold(Money amount) {
        requireCurrency(amount);
        if (availableBalance().isLessThan(amount)) {
            throw new BusinessRuleException(LedgerErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance " + availableBalance() + " is lower than " + amount);
        }
        availableBalance = availableBalance().minus(amount).amount();
    }

    void releaseHold(Money amount) {
        requireCurrency(amount);
        Money released = availableBalance().plus(amount);
        if (released.isGreaterThan(ledgerBalance())) {
            throw new IllegalStateException("Releasing " + amount + " would put the available balance " + released
                    + " above the ledger balance " + ledgerBalance());
        }
        availableBalance = released.amount();
    }

    private void requireCurrency(Money amount) {
        if (!amount.hasCurrency(currency)) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
    }
}
