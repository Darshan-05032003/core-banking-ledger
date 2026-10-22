package com.darshan.ledger.customer;

import com.darshan.ledger.security.AuthenticatedCustomer;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

// Used from @PreAuthorize expressions: a CUSTOMER login may look at its own customer record only.
@Component("customerAccess")
public class CustomerAccess {

    public boolean isSelf(Long customerId, Authentication authentication) {
        return customerId != null && AuthenticatedCustomer.customerIdOf(authentication)
                .map(customerId::equals)
                .orElse(false);
    }
}
