package com.darshan.ledger.account;

import org.springframework.stereotype.Component;

// No posting service exists yet, so no money can have reached any account: every balance is zero
// and nothing is on hold. The ledger module replaces this bean with one reading account_balance.
@Component
class UnfundedAccountBalances implements AccountBalances {

    @Override
    public AccountBalanceSnapshot snapshot(Account account) {
        return AccountBalanceSnapshot.empty(account.getCurrency());
    }
}
