package com.darshan.ledger.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectKycRequest(@NotBlank @Size(max = 255) String reason) {
}
