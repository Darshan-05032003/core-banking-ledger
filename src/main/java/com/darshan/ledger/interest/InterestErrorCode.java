package com.darshan.ledger.interest;

import com.darshan.ledger.common.error.ErrorCode;

public enum InterestErrorCode implements ErrorCode {
    ACCRUAL_DATE_NOT_ENDED;

    @Override
    public String code() {
        return name();
    }
}
