package com.darshan.ledger.statement;

import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalType;
import java.math.BigDecimal;
import java.time.Instant;

// One ledger line of the account with the balance after it. A credit raises the balance of a
// customer account and a debit lowers it, because the balance is what the bank owes the customer.
public record StatementLine(
        long entryId,
        long journalId,
        String journalReference,
        JournalType journalType,
        String description,
        Instant postedAt,
        EntryDirection direction,
        BigDecimal amount,
        StatementCounterparty counterparty,
        BigDecimal runningBalance) {
}
