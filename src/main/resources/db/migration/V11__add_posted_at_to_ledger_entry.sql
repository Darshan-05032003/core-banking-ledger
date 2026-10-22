-- A statement reads one account's lines for a date range in posting order. With the posting time
-- only on journal_entry, PostgreSQL has to fetch every line of the account, join each to its
-- journal to learn its time, and sort the result before it can apply the range. Copying posted_at
-- onto the line lets one index serve the account, the range and the order at once.
ALTER TABLE ledger_entry ADD COLUMN posted_at TIMESTAMP WITH TIME ZONE;

-- The append-only trigger would reject this one-time backfill; it is switched off for the
-- statement and back on in the same transaction, so no window exists in which rows can change.
ALTER TABLE ledger_entry DISABLE TRIGGER trg_ledger_entry_append_only;
UPDATE ledger_entry le
SET posted_at = je.posted_at
FROM journal_entry je
WHERE je.id = le.journal_entry_id;
ALTER TABLE ledger_entry ENABLE TRIGGER trg_ledger_entry_append_only;

ALTER TABLE ledger_entry ALTER COLUMN posted_at SET NOT NULL;

-- The copy can never disagree with the journal: the composite foreign key only accepts the pair
-- (journal id, posted_at) that exists on the journal row, and neither table allows updates.
ALTER TABLE journal_entry ADD CONSTRAINT uk_journal_entry_id_posted_at UNIQUE (id, posted_at);
ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_entry_journal_posted_at
        FOREIGN KEY (journal_entry_id, posted_at) REFERENCES journal_entry (id, posted_at);

-- Keyset pagination and the running balance both order by (posted_at, id); the index returns the
-- lines of one account already in that order, so a page is a range scan without a sort. It
-- replaces the single-column account index, whose lookups it serves through its leading column.
CREATE INDEX ix_ledger_entry_account_posted_at ON ledger_entry (account_id, posted_at, id)
    WHERE account_id IS NOT NULL;
DROP INDEX ix_ledger_entry_account_id;
