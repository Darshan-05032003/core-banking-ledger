package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.money.Money;
import java.util.Currency;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Over-the-counter cash. A deposit moves cash into the vault (asset up, debit) and raises what
// the bank owes the customer (liability up, credit); a withdrawal is the mirror image.
@Service
@Transactional
public class TellerService {

    private static final Logger log = LoggerFactory.getLogger(TellerService.class);

    private final AccountService accountService;
    private final GlAccountRepository glAccountRepository;
    private final PostingService postingService;
    private final JournalMapper journalMapper;

    public TellerService(AccountService accountService, GlAccountRepository glAccountRepository,
            PostingService postingService, JournalMapper journalMapper) {
        this.accountService = accountService;
        this.glAccountRepository = glAccountRepository;
        this.postingService = postingService;
        this.journalMapper = journalMapper;
    }

    public CashOperationResponse deposit(Long accountId, CashRequest request) {
        Account account = accountService.requireAccount(accountId);
        account.requireActive("credited");
        Money amount = amountOf(request);
        GlAccount vault = glAccountRepository.require(GlAccountCode.CASH_VAULT);
        JournalEntry journal = postingService.post(Posting.of(JournalType.DEPOSIT,
                description(request, "Cash deposit"),
                List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(account, amount))));
        log.info("Deposit {} into account {} posted as {}", amount, account.getAccountNumber(), journal.getReference());
        return respond(journal, accountId);
    }

    public CashOperationResponse withdraw(Long accountId, CashRequest request) {
        Account account = accountService.requireAccount(accountId);
        account.requireActive("debited");
        Money amount = amountOf(request);
        GlAccount vault = glAccountRepository.require(GlAccountCode.CASH_VAULT);
        JournalEntry journal = postingService.post(Posting.of(JournalType.WITHDRAWAL,
                description(request, "Cash withdrawal"),
                List.of(PostingLeg.debit(account, amount), PostingLeg.credit(vault, amount))));
        log.info("Withdrawal {} from account {} posted as {}", amount, account.getAccountNumber(),
                journal.getReference());
        return respond(journal, accountId);
    }

    private CashOperationResponse respond(JournalEntry journal, Long accountId) {
        return new CashOperationResponse(journalMapper.toResponse(journal), accountService.balance(accountId));
    }

    private static Money amountOf(CashRequest request) {
        try {
            return Money.of(request.amount(), Currency.getInstance(request.currency()));
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException(AccountErrorCode.UNSUPPORTED_CURRENCY,
                    request.currency() + " is not a known ISO 4217 currency");
        }
    }

    private static String description(CashRequest request, String fallback) {
        return request.remarks() == null || request.remarks().isBlank() ? fallback : request.remarks().trim();
    }
}
