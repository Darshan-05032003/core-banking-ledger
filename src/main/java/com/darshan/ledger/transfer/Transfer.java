package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.JournalEntry;
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
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// One transfer attempt. A rejected attempt is kept as FAILED with the rule that stopped it, so
// every reference a customer quotes can be looked up, whether or not money moved.
@Entity
@Table(name = "transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 20)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false, updatable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false, updatable = false)
    private Account toAccount;

    @Embedded
    private Money amount;

    @Column(updatable = false, length = 200)
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id")
    private JournalEntry journal;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private Transfer(String reference, Account fromAccount, Account toAccount, Money amount, String remarks,
            String createdBy, Instant createdAt) {
        this.reference = Objects.requireNonNull(reference);
        this.fromAccount = Objects.requireNonNull(fromAccount);
        this.toAccount = Objects.requireNonNull(toAccount);
        this.amount = Objects.requireNonNull(amount);
        this.remarks = remarks;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Transfer completed(String reference, Account fromAccount, Account toAccount, Money amount,
            String remarks, JournalEntry journal, String createdBy, Instant now) {
        Transfer transfer = new Transfer(reference, fromAccount, toAccount, amount, remarks, createdBy, now);
        transfer.status = TransferStatus.COMPLETED;
        transfer.journal = Objects.requireNonNull(journal);
        transfer.completedAt = now;
        return transfer;
    }

    public static Transfer failed(String reference, Account fromAccount, Account toAccount, Money amount,
            String remarks, ErrorCode failureCode, String failureReason, String createdBy, Instant now) {
        Transfer transfer = new Transfer(reference, fromAccount, toAccount, amount, remarks, createdBy, now);
        transfer.status = TransferStatus.FAILED;
        transfer.failureCode = Objects.requireNonNull(failureCode).code();
        transfer.failureReason = failureReason;
        transfer.completedAt = now;
        return transfer;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Transfer that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Transfer.class.hashCode();
    }
}
