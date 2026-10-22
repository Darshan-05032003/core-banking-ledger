package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountBalanceSnapshot;
import com.darshan.ledger.account.AccountBalances;
import org.springframework.stereotype.Component;

@Component
class LedgerAccountBalances implements AccountBalances {

    private final AccountBalanceRepository accountBalanceRepository;

    LedgerAccountBalances(AccountBalanceRepository accountBalanceRepository) {
        this.accountBalanceRepository = accountBalanceRepository;
    }

    @Override
    public void initialize(Account account) {
        accountBalanceRepository.save(new AccountBalance(account));
    }

    @Override
    public AccountBalanceSnapshot snapshot(Account account) {
        return accountBalanceRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalStateException("Account " + account.getId() + " has no balance row"))
                .snapshot();
    }
}
