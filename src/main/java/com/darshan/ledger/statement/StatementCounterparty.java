package com.darshan.ledger.statement;

import com.darshan.ledger.ledger.GlAccountCode;

// The other side of the journal: another customer's account (masked) or one of the bank's own
// accounts, such as the cash vault for a counter deposit.
public record StatementCounterparty(String maskedAccountNumber, GlAccountCode glAccount) {
}
