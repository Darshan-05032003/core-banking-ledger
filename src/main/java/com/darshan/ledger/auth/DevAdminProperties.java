package com.darshan.ledger.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Bound only in the dev profile; see application-dev.yml.
@ConfigurationProperties("app.security.dev-admin")
public record DevAdminProperties(String username, String password) {
}
