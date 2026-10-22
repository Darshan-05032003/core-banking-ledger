package com.darshan.ledger.notification;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.outbox.LedgerTopics;
import com.darshan.ledger.outbox.LedgerTransactionPostedV1;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.ObjectMapper;

// These tests bypass the outbox and put records on the topic directly, which is the only way to
// produce what the relay never would: the same event twice, and records that are not events.
@IntegrationTest
class NotificationConsumerIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationProperties notificationProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theSameEventDeliveredTwiceProducesOneNotificationAndOneProcessedRow() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        LedgerTransactionPostedV1 event = event(accountId, UUID.randomUUID(), "75.00");
        LedgerTransactionPostedV1 later = event(accountId, UUID.randomUUID(), "1.00");

        send(event);
        send(event);
        send(later);

        // Same key, same partition: once the later event is processed, both copies of the first were seen.
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationRepository.findByAccountIdOrderByIdAsc(accountId))
                .extracting(Notification::getEventId).contains(later.eventId()));
        List<Notification> notifications = notificationRepository.findByAccountIdOrderByIdAsc(accountId);
        assertThat(notifications).extracting(Notification::getEventId).containsExactly(event.eventId(), later.eventId());
        assertThat(processedRows(event.eventId())).isEqualTo(1);
        assertThat(processedRows(later.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select consumer_group from processed_event where event_id = ?", String.class,
                event.eventId())).isEqualTo(notificationProperties.groupId());
    }

    @Test
    void recordsTheConsumerCannotProcessEndUpInTheDeadLetterTopicWithTheirHeaders() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        String key = String.valueOf(accountId);
        String notJson = "{\"schemaVersion\":1,\"eventId\":";
        String unknownVersion = objectMapper.writeValueAsString(withVersion(event(accountId, UUID.randomUUID(), "5.00"), 99));
        String unknownAccount = objectMapper.writeValueAsString(event(Long.MAX_VALUE, UUID.randomUUID(), "5.00"));
        Map<String, String> poison = Map.of("poison-json", notJson, "poison-version", unknownVersion,
                "poison-account", unknownAccount);
        for (Map.Entry<String, String> entry : poison.entrySet()) {
            ProducerRecord<String, String> record = new ProducerRecord<>(LedgerTopics.TRANSACTIONS, key, entry.getValue());
            record.headers().add(LedgerTopics.EVENT_ID_HEADER, entry.getKey().getBytes(UTF_8));
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
        }
        LedgerTransactionPostedV1 healthy = event(accountId, UUID.randomUUID(), "9.00");
        send(healthy);

        List<ConsumerRecord<String, String>> deadLetters = new ArrayList<>();
        try (Consumer<String, String> consumer = deadLetterConsumer()) {
            await().atMost(WAIT).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (poison.containsKey(header(record, LedgerTopics.EVENT_ID_HEADER))) {
                        deadLetters.add(record);
                    }
                });
                assertThat(deadLetters).hasSize(poison.size());
            });
        }

        assertThat(deadLetters).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo(LedgerTopics.TRANSACTIONS_DLT);
            assertThat(record.key()).isEqualTo(key);
            assertThat(record.value()).isEqualTo(poison.get(header(record, LedgerTopics.EVENT_ID_HEADER)));
            assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(LedgerTopics.TRANSACTIONS);
            assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                    .contains(UnsupportedEventException.class.getName());
        });
        assertThat(deadLetters).extracting(record -> header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .anySatisfy(message -> assertThat(message).contains("not a readable LedgerTransactionPosted event"))
                .anySatisfy(message -> assertThat(message).contains("schema version 99"))
                .anySatisfy(message -> assertThat(message).contains("does not exist"));

        // The consumer moved past the poison records and keeps processing.
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationRepository.findByAccountIdOrderByIdAsc(accountId))
                .extracting(Notification::getEventId).containsExactly(healthy.eventId()));
        assertThat(jdbc.queryForObject("select count(*) from notification where event_id is null", Integer.class))
                .isZero();
    }

    private void send(LedgerTransactionPostedV1 event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(LedgerTopics.TRANSACTIONS,
                String.valueOf(event.accountId()), objectMapper.writeValueAsString(event));
        record.headers().add(LedgerTopics.EVENT_ID_HEADER, event.eventId().toString().getBytes(UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private LedgerTransactionPostedV1 event(long accountId, UUID eventId, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new LedgerTransactionPostedV1(LedgerTransactionPostedV1.SCHEMA_VERSION, eventId, 1L, "JRN-0000000001",
                JournalType.DEPOSIT, NOW, null, accountId, "XXXXXXXX0001", EntryDirection.CREDIT, value, "INR",
                List.of(new LedgerTransactionPostedV1.Leg(null, null, "CASH_VAULT", EntryDirection.DEBIT, value),
                        new LedgerTransactionPostedV1.Leg(accountId, "XXXXXXXX0001", null, EntryDirection.CREDIT, value)));
    }

    private static LedgerTransactionPostedV1 withVersion(LedgerTransactionPostedV1 event, int schemaVersion) {
        return new LedgerTransactionPostedV1(schemaVersion, event.eventId(), event.journalId(), event.journalReference(),
                event.journalType(), event.postedAt(), event.reversalOfJournalId(), event.accountId(),
                event.maskedAccountNumber(), event.direction(), event.amount(), event.currency(), event.legs());
    }

    // Built from the application's consumer factory, which is what knows the container's address.
    private Consumer<String, String> deadLetterConsumer() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null,
                null, overrides);
        consumer.subscribe(List.of(LedgerTopics.TRANSACTIONS_DLT));
        return consumer;
    }

    private int processedRows(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class, eventId);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), UTF_8);
    }
}
