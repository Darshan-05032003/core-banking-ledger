package com.darshan.ledger.auth;

import jakarta.validation.constraints.NotNull;

public record LinkCustomerRequest(@NotNull Long customerId) {
}
