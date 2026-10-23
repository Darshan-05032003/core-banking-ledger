package com.darshan.ledger.outbox;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

// Spring Boot auto-configures a health indicator for the database but none for Kafka, so this
// one asks the cluster to describe itself with the same connection settings the relay and the
// consumer use. It is part of the readiness group: an instance that cannot reach the broker
// still serves balances, but it must not be the one the outbox relay or the consumer run on.
// The timeout is short on purpose; a probe that hangs is worse than one that says DOWN.
@Component
class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final Admin admin;

    KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka health check failed");
        this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        DescribeClusterResult cluster = admin.describeCluster(
                new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
        Collection<Node> brokers = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (brokers.isEmpty()) {
            builder.down().withDetail("reason", "No brokers in the cluster");
            return;
        }
        builder.up()
                .withDetail("clusterId", cluster.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .withDetail("brokers", brokers.size());
    }

    @Override
    public void destroy() {
        admin.close(Duration.ofSeconds(5));
    }
}
