package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.security.CurrentUser;
import java.time.Clock;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The only code path that moves money. A journal is accepted only when its legs are in one
// currency and the debits equal the credits; then the journal, its legs and the affected
// balances are written in one transaction, so the books can never be half-updated.
@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final JournalReferenceGenerator referenceGenerator;
    private final CurrentUser currentUser;
    private final Clock clock;

    public PostingService(JournalEntryRepository journalEntryRepository,
            AccountBalanceRepository accountBalanceRepository, JournalReferenceGenerator referenceGenerator,
            CurrentUser currentUser, Clock clock) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.referenceGenerator = referenceGenerator;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public JournalEntry post(Posting posting) {
        Money total = requireBalanced(posting.legs());
        Map<Long, AccountBalance> balances = lockCustomerBalances(posting.legs(), total.currency());
        applyToBalances(posting.legs(), balances);

        JournalEntry journal = new JournalEntry(referenceGenerator.next(), posting.type(), posting.description(),
                clock.instant(), currentUser.name(), posting.reversalOfId());
        posting.legs().forEach(leg -> journal.addEntry(leg.account(), leg.direction(), leg.amount()));
        journalEntryRepository.save(journal);
        log.info("Journal {} ({}) posted for {} across {} legs", journal.getReference(), journal.getType(), total,
                posting.legs().size());
        return journal;
    }

    private static Money requireBalanced(List<PostingLeg> legs) {
        Currency currency = legs.getFirst().amount().currency();
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (PostingLeg leg : legs) {
            if (!leg.amount().hasCurrency(currency)) {
                throw new BusinessRuleException(LedgerErrorCode.MIXED_CURRENCY_JOURNAL,
                        "A journal must be in a single currency, found " + currency + " and " + leg.amount().currency());
            }
            if (leg.isDebit()) {
                debits = debits.plus(leg.amount());
            } else {
                credits = credits.plus(leg.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new BusinessRuleException(LedgerErrorCode.UNBALANCED_JOURNAL,
                    "Debits " + debits + " do not equal credits " + credits);
        }
        return debits;
    }

    // Rows are locked in ascending account id so two postings touching the same accounts always
    // wait for each other in the same order and cannot deadlock.
    private Map<Long, AccountBalance> lockCustomerBalances(List<PostingLeg> legs, Currency currency) {
        Map<Long, Account> accounts = new TreeMap<>();
        for (PostingLeg leg : legs) {
            if (leg.account() instanceof AccountRef.CustomerAccountRef(Account account)) {
                account.requireNotClosed();
                if (!account.getCurrency().equals(currency)) {
                    throw new BusinessRuleException(LedgerErrorCode.CURRENCY_MISMATCH,
                            "Account " + account.getAccountNumber() + " is held in " + account.getCurrency()
                                    + ", not " + currency);
                }
                accounts.put(account.getId(), account);
            }
        }
        Map<Long, AccountBalance> balances = new LinkedHashMap<>();
        accounts.keySet().forEach(accountId -> balances.put(accountId, accountBalanceRepository
                .lockByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Account " + accountId + " has no balance row"))));
        return balances;
    }

    private static void applyToBalances(List<PostingLeg> legs, Map<Long, AccountBalance> balances) {
        for (PostingLeg leg : legs) {
            if (leg.account() instanceof AccountRef.CustomerAccountRef(Account account)) {
                AccountBalance balance = balances.get(account.getId());
                switch (leg.direction()) {
                    case DEBIT -> balance.debit(leg.amount());
                    case CREDIT -> balance.credit(leg.amount());
                }
            }
        }
    }
}
