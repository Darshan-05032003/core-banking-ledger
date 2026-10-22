package com.darshan.ledger.statement;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.statement")
public record StatementProperties(@NotNull Period maxPeriod) {

    @AssertTrue(message = "max period must be a positive period")
    public boolean isMaxPeriodPositive() {
        return maxPeriod == null || !(maxPeriod.isZero() || maxPeriod.isNegative());
    }
}
