package com.darshan.ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.account")
public record AccountProperties(
        @NotBlank @Pattern(regexp = "[0-9]{4}") String branchCode,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency) {
}
