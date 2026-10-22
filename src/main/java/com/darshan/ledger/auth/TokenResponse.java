package com.darshan.ledger.auth;

import java.time.Instant;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {

    public static final String BEARER = "Bearer";
}
