package com.darshan.ledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    boolean existsByReversalOfId(Long journalId);
}
