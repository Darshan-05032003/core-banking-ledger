package com.darshan.ledger.auth;

import com.darshan.ledger.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,50}",
                message = "must be 3 to 50 letters, digits, dots, underscores or hyphens") String username,
        @NotBlank @Size(min = 12, max = 128) String password,
        @NotEmpty Set<Role> roles,
        Long customerId) {

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", roles=" + roles + ", customerId=" + customerId + "]";
    }
}
