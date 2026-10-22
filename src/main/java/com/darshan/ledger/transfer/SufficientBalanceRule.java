package com.darshan.ledger.transfer;

import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.LedgerErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Last on purpose: it reads the balance that was locked for this transaction, so the answer is
// still true when the debit is posted a moment later.
@Component
@Order(80)
public class SufficientBalanceRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        if (context.senderBalance() == null) {
            throw new IllegalStateException("The sender's balance must be locked before the balance rule runs");
        }
        Money available = context.senderBalance().availableBalance();
        if (available.isLessThan(context.amount())) {
            return TransferRuleResult.rejected(LedgerErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance " + available + " is lower than " + context.amount());
        }
        return TransferRuleResult.allowed();
    }
}
