package com.darshan.ledger.ledger;

import java.math.BigDecimal;

public record UnbalancedJournal(long journalId, String reference, int legs, BigDecimal debits, BigDecimal credits) {
}
