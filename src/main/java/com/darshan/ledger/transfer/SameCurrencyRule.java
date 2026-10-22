package com.darshan.ledger.transfer;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.ledger.LedgerErrorCode;
import java.util.Currency;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// No currency conversion exists in this ledger, so the request and both accounts must agree.
@Component
@Order(40)
public class SameCurrencyRule implements TransferRule {

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        Currency currency = context.amount().currency();
        if (!context.sender().getCurrency().equals(currency)) {
            return rejected("Sender", context.sender(), currency);
        }
        if (!context.beneficiary().getCurrency().equals(currency)) {
            return rejected("Beneficiary", context.beneficiary(), currency);
        }
        return TransferRuleResult.allowed();
    }

    private static TransferRuleResult rejected(String role, Account account, Currency requested) {
        return TransferRuleResult.rejected(LedgerErrorCode.CURRENCY_MISMATCH,
                role + " account " + account.getAccountNumber() + " is held in " + account.getCurrency()
                        + ", not " + requested);
    }
}
