package com.darshan.ledger.transfer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class DistinctAccountsRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        if (context.sender().getId().equals(context.beneficiary().getId())) {
            return TransferRuleResult.rejected(TransferErrorCode.SAME_ACCOUNT,
                    "A transfer needs two different accounts");
        }
        return TransferRuleResult.allowed();
    }
}
