package com.darshan.ledger.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public enum Role {
    CUSTOMER,
    TELLER,
    CHECKER,
    ADMIN;

    public static final String AUTHORITY_PREFIX = "ROLE_";

    public GrantedAuthority authority() {
        return new SimpleGrantedAuthority(AUTHORITY_PREFIX + name());
    }
}
