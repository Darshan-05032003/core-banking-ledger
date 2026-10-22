package com.darshan.ledger.interest;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.interest")
public record InterestProperties(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal savingsAnnualRate,
        @NotBlank String accrualCron) {

    @AssertTrue(message = "accrual cron must be a valid Spring cron expression")
    public boolean isAccrualCronValid() {
        return accrualCron == null || CronExpression.isValidExpression(accrualCron);
    }
}
