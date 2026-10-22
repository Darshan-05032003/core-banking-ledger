package com.darshan.ledger.account;

// Balances belong to the ledger module. The account module asks through this port so the
// dependency points from ledger to account, never back.
public interface AccountBalances {

    void initialize(Account account);

    AccountBalanceSnapshot snapshot(Account account);
}
