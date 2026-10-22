package com.darshan.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Topics are declared, not auto-created on first use, so the partition count is a decision and
// not whatever the broker default happens to be. The dead-letter topic has the same number of
// partitions because a dead-lettered record is republished to the partition it came from.
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfiguration {

    @Bean
    NewTopic ledgerTransactionsTopic(OutboxProperties properties) {
        return TopicBuilder.name(LedgerTopics.TRANSACTIONS)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicationFactor())
                .build();
    }

    @Bean
    NewTopic ledgerTransactionsDeadLetterTopic(OutboxProperties properties) {
        return TopicBuilder.name(LedgerTopics.TRANSACTIONS_DLT)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicationFactor())
                .build();
    }
}
