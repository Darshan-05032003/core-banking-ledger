package com.darshan.ledger.common.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends LedgerException {

    public ResourceNotFoundException(String resource, Object identifier) {
        super(HttpStatus.NOT_FOUND, CommonErrorCode.RESOURCE_NOT_FOUND, resource + " " + identifier + " not found");
    }
}
