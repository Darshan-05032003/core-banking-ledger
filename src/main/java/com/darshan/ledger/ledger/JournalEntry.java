package com.darshan.ledger.ledger;

import com.darshan.ledger.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// A journal is one business event (a deposit, a withdrawal, a reversal) made of two or more legs
// whose debits equal their credits. Every column is insert-only; the database enforces it too.
@Entity
@Table(name = "journal_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 20)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JournalType type;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "reversal_of", updatable = false)
    private Long reversalOfId;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<LedgerEntry> entries = new ArrayList<>();

    JournalEntry(String reference, JournalType type, String description, Instant postedAt, String createdBy,
            Long reversalOfId) {
        this.reference = Objects.requireNonNull(reference);
        this.type = Objects.requireNonNull(type);
        this.description = Objects.requireNonNull(description);
        this.postedAt = Objects.requireNonNull(postedAt);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.reversalOfId = reversalOfId;
    }

    void addEntry(AccountRef accountRef, EntryDirection direction, Money amount) {
        entries.add(new LedgerEntry(this, accountRef, direction, amount));
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean isReversal() {
        return type == JournalType.REVERSAL;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof JournalEntry that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return JournalEntry.class.hashCode();
    }
}
