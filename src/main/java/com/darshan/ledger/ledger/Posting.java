package com.darshan.ledger.ledger;

import java.util.List;
import java.util.Objects;

// Everything the posting service needs to record one business event. The legs are checked for
// balance by the service; this record only guards its own shape.
public record Posting(JournalType type, String description, List<PostingLeg> legs, Long reversalOfId) {

    public Posting {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(description, "description must not be null");
        legs = List.copyOf(legs);
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two legs, got " + legs.size());
        }
        if ((type == JournalType.REVERSAL) != (reversalOfId != null)) {
            throw new IllegalArgumentException("Exactly the REVERSAL type must reference the journal it reverses");
        }
    }

    public static Posting of(JournalType type, String description, List<PostingLeg> legs) {
        return new Posting(type, description, legs, null);
    }

    public static Posting reversalOf(Long originalJournalId, String description, List<PostingLeg> legs) {
        return new Posting(JournalType.REVERSAL, description, legs, Objects.requireNonNull(originalJournalId));
    }
}
