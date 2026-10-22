package com.darshan.ledger.outbox;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.ledger.CashRequest;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalEntryRepository;
import com.darshan.ledger.ledger.JournalPosted;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.TellerService;
import com.darshan.ledger.notification.Notification;
import com.darshan.ledger.notification.NotificationChannel;
import com.darshan.ledger.notification.NotificationRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// The relay is not scheduled in the test profile, so every test drives it by hand; the Kafka
// listener, on the other hand, runs for real against the Kafka container, which is why the
// assertions on notifications wait with Awaitility.
@IntegrationTest
class OutboxIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Pattern JOURNAL_REFERENCE = Pattern.compile("JRN-\\d{10}");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TellerService tellerService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCompletedTransferLeavesOneEventPerCustomerAccountAndBothReachTheConsumer() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        drainOutbox();

        String body = transfer(senderId, beneficiaryId, "250.50", "Rent")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long journalId = ((Number) JsonPath.read(body, "$.journalId")).longValue();
        String journalReference = JsonPath.read(body, "$.journalReference");

        List<Map<String, Object>> rows = outboxRowsOf(journalId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("partition_key"))
                .containsExactly(String.valueOf(senderId), String.valueOf(beneficiaryId));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("aggregate_type")).isEqualTo("JOURNAL");
            assertThat(row.get("event_type")).isEqualTo("LedgerTransactionPosted");
            assertThat(row.get("event_version")).isEqualTo(1);
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(row.get("last_error")).isNull();
        });
        LedgerTransactionPostedV1 senderEvent = objectMapper.readValue((String) rows.get(0).get("payload"),
                LedgerTransactionPostedV1.class);
        assertThat(senderEvent.schemaVersion()).isEqualTo(1);
        assertThat(senderEvent.eventId()).isEqualTo(UUID.fromString((String) rows.get(0).get("id")));
        assertThat(senderEvent.journalId()).isEqualTo(journalId);
        assertThat(senderEvent.journalReference()).isEqualTo(journalReference);
        assertThat(senderEvent.journalType()).isEqualTo(JournalType.TRANSFER);
        assertThat(senderEvent.postedAt()).isEqualTo(NOW);
        assertThat(senderEvent.accountId()).isEqualTo(senderId);
        assertThat(senderEvent.maskedAccountNumber()).startsWith("XXXXXXXX")
                .endsWith(testAccounts.accountNumber(senderId).substring(8));
        assertThat(senderEvent.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(senderEvent.amount()).isEqualByComparingTo("250.50");
        assertThat(senderEvent.currency()).isEqualTo("INR");
        assertThat(senderEvent.legs()).hasSize(2);
        assertThat(senderEvent.legs().get(1).accountId()).isEqualTo(beneficiaryId);
        assertThat(senderEvent.legs().get(1).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat((String) rows.get(0).get("payload")).doesNotContain(testAccounts.accountNumber(senderId))
                .doesNotContain(testAccounts.accountNumber(beneficiaryId));

        assertThat(relay.relayOnce()).isEqualTo(2);

        assertThat(outboxRowsOf(journalId)).allSatisfy(row -> {
            assertThat(((Timestamp) row.get("published_at")).toInstant()).isEqualTo(NOW);
            assertThat(row.get("attempts")).isEqualTo(0);
        });
        List<Notification> notifications = awaitNotificationsOf(journalId);
        Notification debit = notifications.get(0);
        assertThat(debit.getEventId()).isEqualTo(senderEvent.eventId());
        assertThat(debit.getAccountId()).isEqualTo(senderId);
        assertThat(debit.getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(debit.getRecipient()).matches("X+\\d{4}");
        assertThat(debit.getMessage()).isEqualTo("INR 250.50 debited from account " + senderEvent.maskedAccountNumber()
                + " on 22 Oct 2026 10:15 (TRANSFER " + journalReference + ")");
        assertThat(debit.getCreatedAt()).isEqualTo(NOW);
        Notification credit = notifications.get(1);
        assertThat(credit.getAccountId()).isEqualTo(beneficiaryId);
        assertThat(credit.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(credit.getRecipient()).matches("f\\*\\*\\*@example\\.com");
        assertThat(credit.getMessage()).contains("credited to account").contains(journalReference);
    }

    @Test
    void anEventIsVisibleInsideThePostingTransactionAndGoneWhenItRollsBack() {
        long accountId = testAccounts.openActiveAccount();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            tellerService.deposit(accountId, new CashRequest(new BigDecimal("100.00"), "INR", "Rolled back"));
            assertThat(outboxRowsForAccount(accountId)).isEqualTo(1);
            assertThat(ledgerLinesOf(accountId)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(outboxRowsForAccount(accountId)).isZero();
        assertThat(ledgerLinesOf(accountId)).isZero();
    }

    @Test
    void theOutboxWriterRefusesToRunOutsideAPostingTransaction() {
        long accountId = testAccounts.openActiveAccount();
        long journalId = tellerService.deposit(accountId, new CashRequest(new BigDecimal("5.00"), "INR", null))
                .journal().id();
        JournalEntry journal = journalEntryRepository.findById(journalId).orElseThrow();

        assertThatThrownBy(() -> events.publishEvent(new JournalPosted(journal)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRowsForAccount(accountId)).as("only the row the deposit wrote").isEqualTo(1);
    }

    @Test
    void aTransferTheRulesRefuseWritesNoEventAndNoNotification() throws Exception {
        long senderId = testAccounts.openFundedAccount("100.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        int senderRowsFromFunding = outboxRowsForAccount(senderId);
        assertThat(senderRowsFromFunding).isEqualTo(1);

        transfer(senderId, beneficiaryId, "250.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
        assertThat(outboxRowsForAccount(senderId)).isEqualTo(senderRowsFromFunding);
        assertThat(outboxRowsForAccount(beneficiaryId)).isZero();

        // A later, valid movement proves the consumer is live and that nothing from the refusal arrives.
        String body = transfer(senderId, beneficiaryId, "40.00", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        relay.run();
        awaitNotificationsOf(((Number) JsonPath.read(body, "$.journalId")).longValue());
        assertThat(notificationRepository.findByAccountIdOrderByIdAsc(beneficiaryId)).hasSize(1);
        assertThat(notificationRepository.findByAccountIdOrderByIdAsc(senderId))
                .as("the funding deposit and the transfer, nothing for the refusal").hasSize(2);
    }

    @Test
    void theRelayIsIdempotentAndTakesTheShedLockWhenRunAsTheScheduledJob() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        drainOutbox();
        String body = transfer(senderId, beneficiaryId, "10.00", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long journalId = ((Number) JsonPath.read(body, "$.journalId")).longValue();

        assertThat(relay.relayOnce()).isEqualTo(2);
        List<Timestamp> publishedAt = outboxRowsOf(journalId).stream()
                .map(row -> (Timestamp) row.get("published_at")).toList();
        assertThat(relay.relayOnce()).isZero();
        relay.run();

        assertThat(outboxEventRepository.countUnpublished()).isZero();
        assertThat(outboxRowsOf(journalId)).extracting(row -> (Timestamp) row.get("published_at"))
                .containsExactlyElementsOf(publishedAt);
        assertThat(jdbc.queryForObject("select locked_by from shedlock where name = ?", String.class,
                OutboxRelay.LOCK_NAME)).isNotBlank();
        assertThat(awaitNotificationsOf(journalId)).hasSize(2);
        assertThat(notificationRepository.findByAccountIdOrderByIdAsc(beneficiaryId)).hasSize(1);
    }

    @Test
    void aBrokerFailureIsRecordedOnTheRowAndTheRowIsPublishedByALaterRun() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        drainOutbox();
        String body = transfer(senderId, beneficiaryId, "20.00", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long journalId = ((Number) JsonPath.read(body, "$.journalId")).longValue();

        assertThat(relayWithBroker(key -> false).relayOnce()).isZero();

        assertThat(outboxRowsOf(journalId)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).startsWith("TimeoutException: Broker unavailable");
        });

        assertThat(relay.relayOnce()).isEqualTo(2);

        assertThat(outboxRowsOf(journalId)).allSatisfy(row -> {
            assertThat(row.get("published_at")).isNotNull();
            assertThat(row.get("attempts")).isEqualTo(1);
        });
        assertThat(awaitNotificationsOf(journalId)).extracting(Notification::getAccountId)
                .containsExactly(senderId, beneficiaryId);
    }

    @Test
    void aFailedEventHoldsBackLaterEventsOfTheSameAccountButNotOtherAccounts() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        drainOutbox();
        transfer(senderId, beneficiaryId, "1.00", null).andExpect(status().isCreated());
        transfer(senderId, beneficiaryId, "2.00", null).andExpect(status().isCreated());
        String sender = String.valueOf(senderId);

        assertThat(relayWithBroker(key -> !key.equals(sender)).relayOnce()).isEqualTo(2);

        List<Map<String, Object>> senderRows = unpublishedRowsForAccount(senderId);
        assertThat(senderRows).hasSize(2);
        assertThat(senderRows.get(0).get("attempts")).as("the first event was tried").isEqualTo(1);
        assertThat(senderRows.get(1).get("attempts")).as("the second waited behind it").isEqualTo(0);
        assertThat(unpublishedRowsForAccount(beneficiaryId)).isEmpty();

        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(unpublishedRowsForAccount(senderId)).isEmpty();
    }

    @Test
    void eventsOfOneAccountReachTheConsumerInPostingOrder() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        drainOutbox();
        List<String> postedReferences = new ArrayList<>();
        List<Long> journalIds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String body = transfer(senderId, beneficiaryId, i + ".00", "Instalment " + i)
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            postedReferences.add(JsonPath.read(body, "$.journalReference"));
            journalIds.add(((Number) JsonPath.read(body, "$.journalId")).longValue());
        }

        relay.run();

        Set<UUID> eventIds = new HashSet<>();
        journalIds.forEach(journalId -> awaitNotificationsOf(journalId)
                .forEach(notification -> eventIds.add(notification.getEventId())));
        for (long accountId : List.of(senderId, beneficiaryId)) {
            assertThat(notificationRepository.findByAccountIdOrderByIdAsc(accountId).stream()
                    .filter(notification -> eventIds.contains(notification.getEventId()))
                    .map(notification -> referenceIn(notification.getMessage()))
                    .toList())
                    .as("account %s", accountId)
                    .containsExactlyElementsOf(postedReferences);
        }
    }

    // The same relay code against a broker that acknowledges only the keys the predicate accepts.
    private OutboxRelay relayWithBroker(Predicate<String> acknowledges) {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> broker = Mockito.mock(KafkaTemplate.class);
        given(broker.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            if (acknowledges.test(record.key())) {
                RecordMetadata metadata = new RecordMetadata(new TopicPartition(record.topic(), 0), 0, 0, 0, 0, 0);
                return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
            }
            return CompletableFuture.failedFuture(new TimeoutException("Broker unavailable"));
        });
        return new OutboxRelay(outboxEventRepository, broker, transactionManager, outboxProperties, clock);
    }

    // Other test classes post journals without relaying them; publishing that backlog first keeps
    // the counts in these tests about the rows they created.
    private void drainOutbox() {
        relay.run();
        assertThat(outboxEventRepository.countUnpublished()).isZero();
    }

    private ResultActions transfer(long fromId, long toId, String amount, String remarks) throws Exception {
        String remarksJson = remarks == null ? "" : ",\"remarks\":\"" + remarks + "\"";
        return mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + testAccounts.accountNumber(fromId) + "\",\"toAccount\":\""
                        + testAccounts.accountNumber(toId) + "\",\"amount\":" + amount + ",\"currency\":\"INR\""
                        + remarksJson + "}"));
    }

    // The journal's events in outbox order, each one once the consumer has recorded it.
    private List<Notification> awaitNotificationsOf(long journalId) {
        List<UUID> eventIds = outboxRowsOf(journalId).stream()
                .map(row -> UUID.fromString((String) row.get("id"))).toList();
        assertThat(eventIds).isNotEmpty();
        await().atMost(WAIT).untilAsserted(() -> assertThat(eventIds)
                .allSatisfy(eventId -> assertThat(notificationRepository.findByEventId(eventId)).isPresent()));
        return eventIds.stream().map(notificationRepository::findByEventId).map(Optional::orElseThrow).toList();
    }

    private List<Map<String, Object>> outboxRowsOf(long journalId) {
        return jdbc.queryForList("select id::text as id, aggregate_type, event_type, event_version, partition_key,"
                + " payload::text as payload, published_at, attempts, last_error from outbox_event"
                + " where aggregate_id = ? order by seq_no", String.valueOf(journalId));
    }

    private List<Map<String, Object>> unpublishedRowsForAccount(long accountId) {
        return jdbc.queryForList("select attempts, last_error from outbox_event where partition_key = ?"
                + " and published_at is null order by seq_no", String.valueOf(accountId));
    }

    private int outboxRowsForAccount(long accountId) {
        return jdbc.queryForObject("select count(*) from outbox_event where partition_key = ?", Integer.class,
                String.valueOf(accountId));
    }

    private int ledgerLinesOf(long accountId) {
        return jdbc.queryForObject("select count(*) from ledger_entry where account_id = ?", Integer.class, accountId);
    }

    private static String referenceIn(String message) {
        Matcher matcher = JOURNAL_REFERENCE.matcher(message);
        assertThat(matcher.find()).as("message carries a journal reference: %s", message).isTrue();
        return matcher.group();
    }
}
