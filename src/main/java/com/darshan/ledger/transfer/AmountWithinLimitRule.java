package com.darshan.ledger.transfer;

import com.darshan.ledger.common.money.Money;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(60)
public class AmountWithinLimitRule implements TransferRule {

    private final TransferLimitsProperties limits;

    public AmountWithinLimitRule(TransferLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        Money amount = context.amount();
        if (!amount.isPositive()) {
            return TransferRuleResult.rejected(TransferErrorCode.INVALID_AMOUNT,
                    "Transfer amount must be greater than zero");
        }
        Money limit = Money.of(limits.perTransaction(), amount.currency());
        if (amount.isGreaterThan(limit)) {
            return TransferRuleResult.rejected(TransferErrorCode.PER_TRANSACTION_LIMIT_EXCEEDED,
                    "Amount " + amount + " exceeds the per-transaction limit of " + limit);
        }
        return TransferRuleResult.allowed();
    }
}
