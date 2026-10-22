package com.darshan.ledger.transfer;

// One check in the transfer validation chain. Rules are Spring beans ordered with @Order; the
// chain runs them in that order and stops at the first rejection, so a rule may rely on every
// earlier rule having passed (for example, that both accounts exist).
public interface TransferRule {

    TransferRuleResult evaluate(TransferContext context);
}
