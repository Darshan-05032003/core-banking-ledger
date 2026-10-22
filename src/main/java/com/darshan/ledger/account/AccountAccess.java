package com.darshan.ledger.account;

import com.darshan.ledger.security.AuthenticatedCustomer;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Used from @PreAuthorize expressions. Ownership is decided by one existence query against the
// customer id carried in the token; an unknown account is simply "not yours", so a customer
// cannot probe which account ids exist.
@Component("accountAccess")
public class AccountAccess {

    private final AccountRepository accountRepository;

    public AccountAccess(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public boolean isOwner(Long accountId, Authentication authentication) {
        return accountId != null && AuthenticatedCustomer.customerIdOf(authentication)
                .map(customerId -> accountRepository.existsByIdAndCustomerId(accountId, customerId))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean ownsAccountNumber(String accountNumber, Authentication authentication) {
        AccountNumber number = parse(accountNumber);
        return number != null && AuthenticatedCustomer.customerIdOf(authentication)
                .map(customerId -> accountRepository.existsByAccountNumberAndCustomerId(number, customerId))
                .orElse(false);
    }

    private static AccountNumber parse(String accountNumber) {
        try {
            return accountNumber == null ? null : new AccountNumber(accountNumber);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
