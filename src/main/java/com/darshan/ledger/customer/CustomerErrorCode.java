package com.darshan.ledger.customer;

import com.darshan.ledger.common.error.ErrorCode;

public enum CustomerErrorCode implements ErrorCode {
    EMAIL_ALREADY_REGISTERED,
    CUSTOMER_UNDERAGE,
    KYC_STATUS_UNCHANGED;

    @Override
    public String code() {
        return name();
    }
}
