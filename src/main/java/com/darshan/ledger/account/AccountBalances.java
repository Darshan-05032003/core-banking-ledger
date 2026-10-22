package com.darshan.ledger.account;

// Balances belong to the ledger module. The account module asks through this port so the
// dependency points from ledger to account, never back.
public interface AccountBalances {

    void initialize(Account account);

    AccountBalanceSnapshot snapshot(Account account);

    // Reads the balance under a row lock, so nothing can post into the account until the caller's
    // transaction ends. Used when a decision such as closing depends on the balance staying put.
    AccountBalanceSnapshot snapshotForUpdate(Account account);
}
