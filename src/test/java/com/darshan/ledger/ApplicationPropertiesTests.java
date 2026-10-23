package com.darshan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.ledger.account.AccountProperties;
import com.darshan.ledger.common.idempotency.IdempotencyProperties;
import com.darshan.ledger.interest.InterestProperties;
import com.darshan.ledger.ledger.LedgerProperties;
import com.darshan.ledger.notification.NotificationProperties;
import com.darshan.ledger.outbox.OutboxProperties;
import com.darshan.ledger.security.SecurityProperties;
import com.darshan.ledger.statement.StatementProperties;
import com.darshan.ledger.transfer.ApprovalProperties;
import com.darshan.ledger.transfer.TransferLimitsProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApplicationPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsLocalDefaultsFromApplicationYaml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            AccountProperties account = context.getBean(AccountProperties.class);
            assertThat(account.branchCode()).isEqualTo("0001");
            assertThat(account.defaultCurrency()).isEqualTo("INR");

            TransferLimitsProperties limits = context.getBean(TransferLimitsProperties.class);
            assertThat(limits.perTransaction()).isEqualByComparingTo(new BigDecimal("500000.00"));
            assertThat(limits.daily()).isEqualByComparingTo(new BigDecimal("2000000.00"));

            ApprovalProperties approval = context.getBean(ApprovalProperties.class);
            assertThat(approval.threshold()).isEqualByComparingTo(new BigDecimal("200000.00"));
            assertThat(approval.pendingExpiry()).isEqualTo(Duration.ofHours(24));
            assertThat(approval.expiryCron()).isEqualTo("0 * * * * *");

            InterestProperties interest = context.getBean(InterestProperties.class);
            assertThat(interest.savingsAnnualRate()).isEqualByComparingTo(new BigDecimal("0.0350"));
            assertThat(interest.accrualCron()).isEqualTo("0 30 0 * * *");

            SecurityProperties security = context.getBean(SecurityProperties.class);
            assertThat(security.jwt().issuer()).isEqualTo("core-banking-ledger");
            assertThat(security.jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(security.jwt().allowGeneratedKeys()).as("dev profile generates keys").isTrue();
            assertThat(security.jwt().hasConfiguredKeys()).isFalse();
            assertThat(security.rateLimit().transfersPerMinute()).isEqualTo(20);

            LedgerProperties ledger = context.getBean(LedgerProperties.class);
            assertThat(ledger.lockTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(ledger.eodCheckCron()).isEqualTo("0 0 1 * * *");
            assertThat(context.getBean(StatementProperties.class).maxPeriod()).isEqualTo(Period.ofYears(1));
            assertThat(context.getBean(IdempotencyProperties.class).inProgressTimeout())
                    .isEqualTo(Duration.ofMinutes(2));

            OutboxProperties outbox = context.getBean(OutboxProperties.class);
            assertThat(outbox.relayDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(outbox.batchSize()).isEqualTo(100);
            assertThat(outbox.publishTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(outbox.topicPartitions()).isEqualTo(3);
            assertThat(outbox.topicReplicationFactor()).isEqualTo((short) 1);

            NotificationProperties notification = context.getBean(NotificationProperties.class);
            assertThat(notification.groupId()).isEqualTo("ledger-notifications");
            assertThat(notification.retryAttempts()).isEqualTo(3);
            assertThat(notification.retryBackoff()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void environmentOverridesAreBound() {
        runner.withPropertyValues("app.limits.per-transaction=1000.00", "app.limits.daily=5000.00")
                .run(context -> {
                    TransferLimitsProperties limits = context.getBean(TransferLimitsProperties.class);
                    assertThat(limits.perTransaction()).isEqualByComparingTo("1000.00");
                    assertThat(limits.daily()).isEqualByComparingTo("5000.00");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.account.branch-code=12",
            "app.account.default-currency=inr",
            "app.limits.per-transaction=-1",
            "app.limits.per-transaction=0.00",
            "app.limits.daily=1.00",
            "app.approval.threshold=0",
            "app.approval.pending-expiry=0s",
            "app.approval.expiry-cron=hourly",
            "app.interest.savings-annual-rate=1.5",
            "app.interest.accrual-cron=every day at noon",
            "app.security.jwt.issuer=",
            "app.security.jwt.access-token-ttl=0s",
            "app.security.jwt.private-key=-----BEGIN PRIVATE KEY-----",
            "app.security.rate-limit.transfers-per-minute=0",
            "app.ledger.lock-timeout=0s",
            "app.ledger.eod-check-cron=nightly",
            "app.idempotency.in-progress-timeout=-1m",
            "app.statement.max-period=0d",
            "app.statement.max-period=-6m",
            "app.outbox.relay-delay=PT0S",
            "app.outbox.batch-size=0",
            "app.outbox.batch-size=5000",
            "app.outbox.publish-timeout=-1s",
            "app.outbox.topic-partitions=0",
            "app.notification.group-id=",
            "app.notification.retry-attempts=0",
            "app.notification.retry-backoff=-1s"
    })
    void invalidValuesFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @EnableConfigurationProperties({
            AccountProperties.class,
            TransferLimitsProperties.class,
            ApprovalProperties.class,
            InterestProperties.class,
            SecurityProperties.class,
            LedgerProperties.class,
            IdempotencyProperties.class,
            StatementProperties.class,
            OutboxProperties.class,
            NotificationProperties.class
    })
    static class PropertiesConfiguration {
    }
}
