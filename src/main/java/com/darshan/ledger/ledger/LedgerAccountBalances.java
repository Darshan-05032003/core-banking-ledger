package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountBalanceSnapshot;
import com.darshan.ledger.account.AccountBalances;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LedgerAccountBalances implements AccountBalances {

    private final AccountBalanceRepository accountBalanceRepository;
    private final PostingService postingService;

    LedgerAccountBalances(AccountBalanceRepository accountBalanceRepository, PostingService postingService) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.postingService = postingService;
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

    @Override
    public AccountBalanceSnapshot snapshotForUpdate(Account account) {
        return postingService.lockBalances(List.of(account)).get(account.getId()).snapshot();
    }
}
