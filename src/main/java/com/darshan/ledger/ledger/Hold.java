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
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Money set aside on an account for a transfer that is not posted yet. The ledger keeps the
// transfer id as a plain number: a hold needs to know what it is reserved for, not how a
// transfer works, and that keeps the dependency pointing from transfer to ledger only.
@Entity
@Table(name = "hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private Long transferId;

    @Embedded
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    Hold(Account account, Long transferId, Money amount, Instant createdAt, Instant expiresAt) {
        this.account = Objects.requireNonNull(account);
        this.transferId = Objects.requireNonNull(transferId);
        this.amount = Objects.requireNonNull(amount);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("A hold must expire after it is placed");
        }
        this.status = HoldStatus.ACTIVE;
    }

    void capture(Instant now) {
        end(HoldStatus.CAPTURED, now);
    }

    void release(Instant now) {
        end(HoldStatus.RELEASED, now);
    }

    public boolean isActive() {
        return status == HoldStatus.ACTIVE;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    private void end(HoldStatus terminal, Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("Hold " + id + " is already " + status);
        }
        status = terminal;
        releasedAt = Objects.requireNonNull(now);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Hold that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Hold.class.hashCode();
    }
}
