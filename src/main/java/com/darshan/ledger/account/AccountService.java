package com.darshan.ledger.account;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.customer.CustomerService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final CustomerService customerService;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountBalances accountBalances;
    private final AccountMapper accountMapper;
    private final AccountProperties properties;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, CustomerService customerService,
            AccountNumberGenerator accountNumberGenerator, AccountBalances accountBalances,
            AccountMapper accountMapper, AccountProperties properties, Clock clock) {
        this.accountRepository = accountRepository;
        this.customerService = customerService;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountBalances = accountBalances;
        this.accountMapper = accountMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public AccountResponse open(OpenAccountRequest request) {
        Customer customer = customerService.requireCustomer(request.customerId());
        if (!customer.isKycVerified()) {
            throw new BusinessRuleException(AccountErrorCode.CUSTOMER_NOT_VERIFIED,
                    "Customer " + customer.getId() + " must be KYC verified before an account can be opened");
        }
        Currency currency = resolveCurrency(request.currency());
        AccountNumber accountNumber = accountNumberGenerator.next();
        Account account = accountRepository.save(new Account(
                accountNumber, customer, request.type(), currency, LocalDate.now(clock), clock.instant()));
        accountBalances.initialize(account);
        log.info("Account {} ({}) opened for customer {}", accountNumber, account.getType(), customer.getId());
        return accountMapper.toResponse(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long accountId) {
        return accountMapper.toResponse(requireAccount(accountId));
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse balance(Long accountId) {
        Account account = requireAccount(accountId);
        return accountMapper.toBalanceResponse(account, accountBalances.snapshot(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listForCustomer(Long customerId) {
        customerService.requireCustomer(customerId);
        return accountMapper.toResponses(accountRepository.findByCustomerIdOrderByIdAsc(customerId));
    }

    public AccountResponse freeze(Long accountId) {
        Account account = requireAccount(accountId);
        account.freeze();
        log.info("Account {} frozen", account.getAccountNumber());
        return accountMapper.toResponse(account);
    }

    public AccountResponse unfreeze(Long accountId) {
        Account account = requireAccount(accountId);
        account.unfreeze();
        log.info("Account {} unfrozen", account.getAccountNumber());
        return accountMapper.toResponse(account);
    }

    public AccountResponse close(Long accountId) {
        Account account = requireAccount(accountId);
        switch (accountBalances.snapshot(account).closureEligibility()) {
            case ClosureEligibility.Eligible eligible -> account.close(LocalDate.now(clock));
            case ClosureEligibility.FundsOnHold holds -> throw new BusinessRuleException(
                    AccountErrorCode.FUNDS_ON_HOLD,
                    "Account has " + holds.held() + " on hold; it cannot be closed until the holds are released");
            case ClosureEligibility.BalanceNotZero balance -> throw new BusinessRuleException(
                    AccountErrorCode.BALANCE_NOT_ZERO,
                    "Account balance is " + balance.balance() + "; it must be exactly zero before closing");
        }
        log.info("Account {} closed", account.getAccountNumber());
        return accountMapper.toResponse(account);
    }

    @Transactional(readOnly = true)
    public Account requireAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private Currency resolveCurrency(String requested) {
        String code = requested == null ? properties.defaultCurrency() : requested;
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException(AccountErrorCode.UNSUPPORTED_CURRENCY,
                    code + " is not a known ISO 4217 currency");
        }
    }
}
