package com.darshan.ledger.approval;

import com.darshan.ledger.common.error.ErrorCode;

public enum ApprovalErrorCode implements ErrorCode {
    SELF_APPROVAL_FORBIDDEN;

    @Override
    public String code() {
        return name();
    }
}
