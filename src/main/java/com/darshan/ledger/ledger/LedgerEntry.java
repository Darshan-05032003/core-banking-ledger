package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    private JournalEntry journal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", updatable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gl_account_id", updatable = false)
    private GlAccount glAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private EntryDirection direction;

    @Embedded
    private Money amount;

    LedgerEntry(JournalEntry journal, AccountRef accountRef, EntryDirection direction, Money amount) {
        this.journal = Objects.requireNonNull(journal);
        this.direction = Objects.requireNonNull(direction);
        this.amount = Objects.requireNonNull(amount);
        switch (accountRef) {
            case AccountRef.CustomerAccountRef customer -> this.account = customer.account();
            case AccountRef.GlAccountRef gl -> this.glAccount = gl.glAccount();
        }
    }

    public AccountRef accountRef() {
        return account != null ? new AccountRef.CustomerAccountRef(account) : new AccountRef.GlAccountRef(glAccount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof LedgerEntry that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return LedgerEntry.class.hashCode();
    }
}
