package com.darshan.ledger.transfer;

import com.darshan.ledger.security.AuthenticatedCustomer;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Used from @PreAuthorize expressions: a customer sees the transfers sent from their own accounts.
@Component("transferAccess")
public class TransferAccess {

    private final TransferRepository transferRepository;

    public TransferAccess(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Transactional(readOnly = true)
    public boolean isSender(Long transferId, Authentication authentication) {
        return transferId != null && AuthenticatedCustomer.customerIdOf(authentication)
                .map(customerId -> transferRepository.existsByIdAndFromAccountCustomerId(transferId, customerId))
                .orElse(false);
    }
}
