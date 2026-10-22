package com.darshan.ledger.account;

import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.customer.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 12)
    private AccountNumber accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "opened_on", nullable = false, updatable = false)
    private LocalDate openedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Account(AccountNumber accountNumber, Customer customer, AccountType type, Currency currency,
            LocalDate openedOn, Instant createdAt) {
        this.accountNumber = Objects.requireNonNull(accountNumber);
        this.customer = Objects.requireNonNull(customer);
        this.type = Objects.requireNonNull(type);
        this.currency = Objects.requireNonNull(currency);
        this.openedOn = Objects.requireNonNull(openedOn);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = AccountStatus.ACTIVE;
    }

    public void freeze() {
        requireActive("frozen");
        status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        requireNotClosed();
        if (status != AccountStatus.FROZEN) {
            throw new ConflictException(AccountErrorCode.ACCOUNT_NOT_FROZEN,
                    "Account " + accountNumber + " is " + status + " and cannot be unfrozen");
        }
        status = AccountStatus.ACTIVE;
    }

    // The balance and hold checks live in AccountService; the entity only guards its own state.
    public void close(LocalDate closedOn) {
        requireNotClosed();
        status = AccountStatus.CLOSED;
        this.closedOn = Objects.requireNonNull(closedOn);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    // The verb completes the message: "is FROZEN and cannot be credited".
    public void requireActive(String verb) {
        requireNotClosed();
        if (status != AccountStatus.ACTIVE) {
            throw new ConflictException(AccountErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Account " + accountNumber + " is " + status + " and cannot be " + verb);
        }
    }

    public void requireNotClosed() {
        if (status == AccountStatus.CLOSED) {
            throw new ConflictException(AccountErrorCode.ACCOUNT_CLOSED,
                    "Account " + accountNumber + " is closed");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Account that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Account.class.hashCode();
    }
}
