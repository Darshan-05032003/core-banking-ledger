package com.darshan.ledger.auth;

import com.darshan.ledger.security.Role;
import java.time.Instant;
import java.util.Set;

public record AppUserResponse(Long id, String username, Set<Role> roles, Long customerId, boolean enabled,
        String createdBy, Instant createdAt) {
}
