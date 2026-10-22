package com.darshan.ledger.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

// The customer a CUSTOMER login acts for, carried in the token so ownership checks need no lookup
// of the user record on every request.
public final class AuthenticatedCustomer {

    private AuthenticatedCustomer() {
    }

    public static Optional<Long> customerIdOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getClaim(JwtClaims.CUSTOMER_ID) instanceof Number customerId) {
            return Optional.of(customerId.longValue());
        }
        return Optional.empty();
    }
}
