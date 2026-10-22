package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.account.AccountStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class AccountsActiveRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        if (!context.sender().isActive()) {
            return rejected("Sender", context.sender());
        }
        if (!context.beneficiary().isActive()) {
            return rejected("Beneficiary", context.beneficiary());
        }
        return TransferRuleResult.allowed();
    }

    private static TransferRuleResult rejected(String role, Account account) {
        AccountErrorCode code = account.getStatus() == AccountStatus.CLOSED
                ? AccountErrorCode.ACCOUNT_CLOSED
                : AccountErrorCode.ACCOUNT_NOT_ACTIVE;
        return TransferRuleResult.rejected(code,
                role + " account " + account.getAccountNumber() + " is " + account.getStatus());
    }
}
