package com.darshan.ledger.ledger;

import com.darshan.ledger.common.error.ErrorCode;

public enum LedgerErrorCode implements ErrorCode {
    UNBALANCED_JOURNAL,
    MIXED_CURRENCY_JOURNAL,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    JOURNAL_ALREADY_REVERSED,
    JOURNAL_NOT_REVERSIBLE;

    @Override
    public String code() {
        return name();
    }
}
