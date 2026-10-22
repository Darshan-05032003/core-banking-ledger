package com.darshan.ledger.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull RateLimit rateLimit) {

    // The keys are either PEM text (environment variables) or a location such as
    // file:/run/secrets/jwt-private.pem. Generated keys are for dev and test only: tokens signed
    // with them die with the process, and a second instance could not verify them.
    public record Jwt(@NotBlank String issuer, @NotNull Duration accessTokenTtl, String privateKey, String publicKey,
            boolean allowGeneratedKeys) {

        @AssertTrue(message = "access token TTL must be a positive duration")
        public boolean isAccessTokenTtlPositive() {
            return accessTokenTtl == null || accessTokenTtl.isPositive();
        }

        @AssertTrue(message = "private and public key must be configured together")
        public boolean isKeyPairComplete() {
            return StringUtils.hasText(privateKey) == StringUtils.hasText(publicKey);
        }

        public boolean hasConfiguredKeys() {
            return StringUtils.hasText(privateKey) && StringUtils.hasText(publicKey);
        }
    }

    public record RateLimit(@Min(1) int transfersPerMinute) {
    }
}
