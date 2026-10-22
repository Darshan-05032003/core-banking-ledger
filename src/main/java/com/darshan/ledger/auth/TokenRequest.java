package com.darshan.ledger.auth;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(@NotBlank String username, @NotBlank String password) {

    @Override
    public String toString() {
        return "TokenRequest[username=" + username + "]";
    }
}
