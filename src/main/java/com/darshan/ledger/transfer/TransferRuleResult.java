package com.darshan.ledger.transfer;

import com.darshan.ledger.common.error.ErrorCode;
import java.util.Objects;

public sealed interface TransferRuleResult {

    record Allowed() implements TransferRuleResult {
    }

    record Rejected(ErrorCode errorCode, String message) implements TransferRuleResult {

        public Rejected {
            Objects.requireNonNull(errorCode, "error code must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    static TransferRuleResult allowed() {
        return new Allowed();
    }

    static TransferRuleResult rejected(ErrorCode errorCode, String message) {
        return new Rejected(errorCode, message);
    }
}
