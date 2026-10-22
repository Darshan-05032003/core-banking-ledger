package com.darshan.ledger.transfer;

import com.darshan.ledger.account.AccountErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Only the sender's identity matters here: money may be received by any open account, but it
// may only be sent by a customer the bank has verified.
@Component
@Order(50)
public class SenderKycVerifiedRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        if (!context.sender().getCustomer().isKycVerified()) {
            return TransferRuleResult.rejected(AccountErrorCode.CUSTOMER_NOT_VERIFIED,
                    "Sender's KYC status is " + context.sender().getCustomer().getKycStatus()
                            + "; only verified customers can send money");
        }
        return TransferRuleResult.allowed();
    }
}
