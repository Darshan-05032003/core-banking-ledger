package com.darshan.ledger.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(@NotBlank @Size(max = 200) String reason) {
}
