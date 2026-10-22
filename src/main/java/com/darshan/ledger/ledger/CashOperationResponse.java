package com.darshan.ledger.ledger;

import com.darshan.ledger.account.AccountBalanceResponse;

public record CashOperationResponse(JournalResponse journal, AccountBalanceResponse balance) {
}
