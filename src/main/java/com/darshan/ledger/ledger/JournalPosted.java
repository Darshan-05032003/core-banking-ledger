package com.darshan.ledger.ledger;

import java.util.Objects;

// Raised by the posting service inside the posting transaction, so a listener that records the
// event elsewhere (the outbox) writes into the same unit of work. The ledger does not know who
// listens, which keeps the dependency pointing from the messaging code towards the ledger.
public record JournalPosted(JournalEntry journal) {

    public JournalPosted {
        Objects.requireNonNull(journal, "journal must not be null");
    }
}
