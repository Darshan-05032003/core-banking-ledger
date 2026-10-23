package com.darshan.ledger;

import static com.darshan.ledger.FixedClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.audit.AuditLog;
import com.darshan.ledger.audit.AuditLogRepository;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.notification.Notification;
import com.darshan.ledger.notification.NotificationChannel;
import com.darshan.ledger.notification.NotificationRepository;
import com.darshan.ledger.outbox.OutboxRelay;
import com.darshan.ledger.security.Role;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// One customer's first day at the bank, driven entirely through the API with a real token for
// each person involved: a teller onboards and funds, the customer pays, a checker authorises
// the large payment, the customer reads the statement, and the bank's downstream systems learn
// about every movement through Kafka. Each step is covered in depth by its own feature's tests;
// this one proves the pieces fit together.
@IntegrationTest
class CustomerJourneyEndToEndTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void fromOnboardingToTheStatementAndTheNotifications() throws Exception {
        TestUser teller = testUsers.create(Role.TELLER);
        TestUser checker = testUsers.create(Role.CHECKER);
        RequestPostProcessor asTeller = TestUsers.bearer(testUsers.tokenFor(teller));
        RequestPostProcessor asChecker = TestUsers.bearer(testUsers.tokenFor(checker));
        String unique = UUID.randomUUID().toString().substring(0, 8);

        // A teller onboards Asha and verifies her identity documents.
        String customerBody = mockMvc.perform(post("/api/v1/customers").with(asTeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Asha Rao\",\"email\":\"asha-" + unique + "@example.com\","
                                + "\"phone\":\"+919876543210\",\"dateOfBirth\":\"1990-05-17\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.maskedEmail").value("a***@example.com"))
                .andReturn().getResponse().getContentAsString();
        long customerId = ((Number) JsonPath.read(customerBody, "$.id")).longValue();

        mockMvc.perform(post("/api/v1/accounts").with(asTeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_VERIFIED"));

        mockMvc.perform(post("/api/v1/customers/{id}/kyc/verify", customerId).with(asTeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));

        // Two accounts, one funded at the counter.
        long savingsId = openAccount(asTeller, customerId, "SAVINGS");
        long currentId = openAccount(asTeller, customerId, "CURRENT");
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(asTeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500000.00,\"currency\":\"INR\",\"remarks\":\"Opening deposit\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journal.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.balance.ledgerBalance").value(500000.00))
                .andExpect(jsonPath("$.balance.availableBalance").value(500000.00));

        // Asha gets a login linked to her customer record and moves money between her accounts.
        TestUser asha = testUsers.createCustomer(customerId);
        RequestPostProcessor asAsha = TestUsers.bearer(testUsers.tokenFor(asha));
        String savingsNumber = testAccounts.accountNumber(savingsId);
        String currentNumber = testAccounts.accountNumber(currentId);

        transfer(asAsha, savingsNumber, currentNumber, "1000.00", "Monthly budget")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.journalReference").value(Matchers.startsWith("JRN-")));

        // The large payment waits for a second pair of eyes; the money is reserved meanwhile.
        String pendingBody = transfer(asAsha, savingsNumber, currentNumber, "250000.00", "Car")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", Matchers.containsString("/api/v1/transfers/")))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.createdBy").value(asha.username()))
                .andReturn().getResponse().getContentAsString();
        long pendingId = ((Number) JsonPath.read(pendingBody, "$.id")).longValue();
        expectBalances(asAsha, savingsId, "499000.00", "249000.00", "250000.00");

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", pendingId).with(asAsha))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/approvals/{id}/approve", pendingId).with(asChecker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decidedBy").value(checker.username()));
        expectBalances(asAsha, savingsId, "249000.00", "249000.00", "0.00");
        expectBalances(asAsha, currentId, "251000.00", "251000.00", "0.00");

        // The statement explains the balance line by line.
        mockMvc.perform(get("/api/v1/accounts/{id}/statement", savingsId).with(asAsha)
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(0.00))
                .andExpect(jsonPath("$.closingBalance").value(249000.00))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].journalType").value("DEPOSIT"))
                .andExpect(jsonPath("$.lines[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.lines[0].runningBalance").value(500000.00))
                .andExpect(jsonPath("$.lines[1].journalType").value("TRANSFER"))
                .andExpect(jsonPath("$.lines[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.lines[1].runningBalance").value(499000.00))
                .andExpect(jsonPath("$.lines[2].description")
                        .value("Transfer " + JsonPath.read(pendingBody, "$.reference") + ": Car"))
                .andExpect(jsonPath("$.lines[2].runningBalance").value(249000.00))
                .andExpect(jsonPath("$.lines[2].counterparty.maskedAccountNumber")
                        .value(Matchers.endsWith(currentNumber.substring(8))))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // Nobody may read somebody else's statement; the attempt is on record.
        TestUser stranger = testUsers.createUnlinkedCustomer();
        String correlationId = "journey-" + unique;
        mockMvc.perform(get("/api/v1/accounts/{id}/statement", savingsId)
                        .with(TestUsers.bearer(testUsers.tokenFor(stranger)))
                        .header("X-Correlation-Id", correlationId)
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isForbidden());
        assertThat(auditLogRepository.findByCorrelationIdOrderByIdAsc(correlationId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo("STATEMENT_VIEW");
                    assertThat(row.getActor()).isEqualTo(stranger.username());
                    assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
                });
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdAsc("TRANSFER", String.valueOf(pendingId)))
                .extracting(AuditLog::getAction, AuditLog::getActor, AuditLog::getOutcome)
                .contains(tuple("TRANSFER_APPROVE", asha.username(), AuditOutcome.FAILURE),
                        tuple("TRANSFER_APPROVE", checker.username(), AuditOutcome.SUCCESS));

        // The outbox relay is not scheduled in tests; one run publishes everything the day produced,
        // and the consumer records a message for every movement on each account.
        outboxRelay.run();
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(notificationRepository.findByAccountIdOrderByIdAsc(savingsId)).hasSize(3);
            assertThat(notificationRepository.findByAccountIdOrderByIdAsc(currentId)).hasSize(2);
        });
        List<Notification> savingsMessages = notificationRepository.findByAccountIdOrderByIdAsc(savingsId);
        assertThat(savingsMessages).extracting(Notification::getChannel)
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.SMS);
        assertThat(savingsMessages).extracting(Notification::getMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(savingsNumber).doesNotContain(currentNumber));
        assertThat(savingsMessages.get(2).getMessage()).startsWith("INR 250000.00 debited from account XXXXXXXX");
        assertThat(notificationRepository.findByAccountIdOrderByIdAsc(currentId))
                .extracting(Notification::getChannel).containsOnly(NotificationChannel.EMAIL);
    }

    private long openAccount(RequestPostProcessor asTeller, long customerId, String type) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts").with(asTeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions transfer(RequestPostProcessor caller, String from, String to, String amount, String remarks)
            throws Exception {
        return mockMvc.perform(post("/api/v1/transfers").with(caller)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to + "\",\"amount\":" + amount
                        + ",\"currency\":\"INR\",\"remarks\":\"" + remarks + "\"}"));
    }

    private void expectBalances(RequestPostProcessor caller, long accountId, String ledger, String available,
            String onHold) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId).with(caller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerBalance").value(Double.parseDouble(ledger)))
                .andExpect(jsonPath("$.availableBalance").value(Double.parseDouble(available)))
                .andExpect(jsonPath("$.onHold").value(Double.parseDouble(onHold)));
    }
}
