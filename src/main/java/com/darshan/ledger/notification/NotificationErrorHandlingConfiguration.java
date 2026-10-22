package com.darshan.ledger.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// What happens when the listener throws. Retryable failures are retried in place with a fixed
// backoff (retryAttempts deliveries in all); then, or at once for an UnsupportedEventException,
// the record is republished to ledger.transactions.v1.DLT with its original key, value and
// headers plus the exception details, and the consumer moves on. Spring Boot wires this bean
// into the listener container factory.
@Configuration(proxyBeanMethods = false)
class NotificationErrorHandlingConfiguration {

    @Bean
    DefaultErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
            NotificationProperties properties) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(properties.retryBackoff().toMillis(), properties.retryAttempts() - 1L);
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backOff);
        handler.addNotRetryableExceptions(UnsupportedEventException.class);
        return handler;
    }
}
