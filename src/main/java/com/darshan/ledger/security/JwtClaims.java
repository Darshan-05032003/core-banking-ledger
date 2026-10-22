package com.darshan.ledger.security;

// Names of the private claims this application puts into its own tokens.
public final class JwtClaims {

    public static final String ROLES = "roles";
    public static final String CUSTOMER_ID = "customer_id";

    private JwtClaims() {
    }
}
