package com.darshan.ledger.transfer;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TransferRuleChain {

    private final List<TransferRule> rules;

    public TransferRuleChain(List<TransferRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public TransferRuleResult evaluate(TransferContext context) {
        for (TransferRule rule : rules) {
            if (rule.evaluate(context) instanceof TransferRuleResult.Rejected rejected) {
                return rejected;
            }
        }
        return TransferRuleResult.allowed();
    }

    public List<TransferRule> rules() {
        return rules;
    }
}
