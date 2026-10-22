package com.darshan.ledger.transfer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class AccountsExistRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        if (!context.hasSender()) {
            return TransferRuleResult.rejected(TransferErrorCode.ACCOUNT_NOT_FOUND,
                    "Sender account " + context.maskedFromAccount() + " does not exist");
        }
        if (!context.hasBeneficiary()) {
            return TransferRuleResult.rejected(TransferErrorCode.ACCOUNT_NOT_FOUND,
                    "Beneficiary account " + context.maskedToAccount() + " does not exist");
        }
        return TransferRuleResult.allowed();
    }
}
