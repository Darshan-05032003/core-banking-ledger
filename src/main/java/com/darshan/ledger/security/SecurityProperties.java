package com.darshan.ledger.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull RateLimit rateLimit) {

    public record Jwt(@NotBlank String issuer, @NotNull Duration accessTokenTtl) {
    }

    public record RateLimit(@Min(1) int transfersPerMinute) {
    }
}
