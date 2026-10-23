package com.darshan.ledger.ledger;

import com.darshan.ledger.common.error.ErrorCode;

public enum LedgerErrorCode implements ErrorCode {
    UNBALANCED_JOURNAL,
    MIXED_CURRENCY_JOURNAL,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    JOURNAL_ALREADY_REVERSED,
    JOURNAL_NOT_REVERSIBLE,
    BALANCE_MISMATCH,
    LEDGER_OUT_OF_BALANCE,
    BUSINESS_DATE_IN_FUTURE;

    @Override
    public String code() {
        return name();
    }
}
