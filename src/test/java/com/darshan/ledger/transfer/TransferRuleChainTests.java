package com.darshan.ledger.transfer;

import static com.darshan.ledger.transfer.TransferFixtures.INR;
import static com.darshan.ledger.transfer.TransferFixtures.context;
import static com.darshan.ledger.transfer.TransferFixtures.verifiedAccount;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferRuleChainTests {

    private final TransferContext context = context(verifiedAccount(2L, INR), verifiedAccount(5L, INR), "10.00");
    private final List<String> evaluated = new ArrayList<>();

    @Test
    void anEmptyChainAllowsEverything() {
        assertThat(new TransferRuleChain(List.of()).evaluate(context)).isInstanceOf(TransferRuleResult.Allowed.class);
    }

    @Test
    void allRulesRunWhenEveryOneAllows() {
        TransferRuleChain chain = new TransferRuleChain(List.of(allowing("first"), allowing("second")));

        assertThat(chain.evaluate(context)).isInstanceOf(TransferRuleResult.Allowed.class);
        assertThat(evaluated).containsExactly("first", "second");
    }

    @Test
    void theFirstRejectionStopsTheChainAndIsReturnedUnchanged() {
        TransferRuleChain chain = new TransferRuleChain(List.of(allowing("first"),
                rejecting("second", TransferErrorCode.SAME_ACCOUNT), rejecting("third", TransferErrorCode.INVALID_AMOUNT)));

        TransferRuleResult result = chain.evaluate(context);

        assertThat(result).isEqualTo(new TransferRuleResult.Rejected(TransferErrorCode.SAME_ACCOUNT, "second"));
        assertThat(evaluated).containsExactly("first", "second");
    }

    private TransferRule allowing(String name) {
        return ctx -> {
            evaluated.add(name);
            return TransferRuleResult.allowed();
        };
    }

    private TransferRule rejecting(String name, TransferErrorCode code) {
        return ctx -> {
            evaluated.add(name);
            return TransferRuleResult.rejected(code, name);
        };
    }
}
