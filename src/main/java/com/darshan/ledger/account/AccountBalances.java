package com.darshan.ledger.account;

// Balances belong to the ledger module, which arrives with the posting service. The account
// module asks through this port so the dependency points from ledger to account, never back.
public interface AccountBalances {

    AccountBalanceSnapshot snapshot(Account account);
}
