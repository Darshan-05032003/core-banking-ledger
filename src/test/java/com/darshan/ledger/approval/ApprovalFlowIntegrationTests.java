package com.darshan.ledger.approval;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.audit.AuditLog;
import com.darshan.ledger.audit.AuditLogRepository;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.security.Role;
import com.darshan.ledger.transfer.ApprovalProperties;
import com.darshan.ledger.transfer.TransferErrorCode;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// The maker is the shared mock ADMIN ("user") unless a test says otherwise; checkers are real
// logins with real tokens, so the four-eyes rule is exercised the way a client would hit it.
@IntegrationTest
class ApprovalFlowIntegrationTests {

    private static final String ABOVE_THRESHOLD = "250000.00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private PendingTransferExpiryJob expiryJob;

    @Autowired
    private ApprovalProperties approvalProperties;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aTransferAboveTheThresholdIsParkedWithAHoldAndAnsweredWith202() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        String key = UUID.randomUUID().toString();

        String body = transfer(key, senderId, beneficiaryId, ABOVE_THRESHOLD, "Car")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/v1/transfers/")))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.journalId").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value("user"))
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(body, "$.id")).longValue();

        expectBalances(senderId, "1000000.00", "750000.00", "250000.00");
        expectBalances(beneficiaryId, "0.00", "0.00", "0.00");
        assertThat(holdStatus(transferId)).isEqualTo("ACTIVE");
        assertThat(holdTime(transferId, "expires_at")).isEqualTo(NOW.plus(approvalProperties.pendingExpiry()));
        assertThat(holdTime(transferId, "released_at")).isNull();

        mockMvc.perform(get("/api/v1/approvals").with(checker()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + transferId + ")].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));

        transfer(key, senderId, beneficiaryId, ABOVE_THRESHOLD, "Car")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        expectBalances(senderId, "1000000.00", "750000.00", "250000.00");
        assertHoldsExplainTheBalanceGap();
    }

    @Test
    void aTransferOfExactlyTheThresholdIsPostedImmediately() throws Exception {
        long senderId = testAccounts.openFundedAccount("300000.00");
        long beneficiaryId = testAccounts.openActiveAccount();

        transfer(UUID.randomUUID().toString(), senderId, beneficiaryId, approvalProperties.threshold().toPlainString(),
                null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        expectBalances(senderId, "100000.00", "100000.00", "0.00");
    }

    @Test
    void heldMoneyCannotBeSpentBySecondTransfer() throws Exception {
        long senderId = testAccounts.openFundedAccount("300000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        transfer(UUID.randomUUID().toString(), senderId, beneficiaryId, ABOVE_THRESHOLD, null)
                .andExpect(status().isAccepted());

        transfer(UUID.randomUUID().toString(), senderId, beneficiaryId, "50000.01", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value(containsString("Available balance 50000.00 INR")));
        transfer(UUID.randomUUID().toString(), senderId, beneficiaryId, "50000.00", null)
                .andExpect(status().isCreated());

        expectBalances(senderId, "250000.00", "0.00", "250000.00");
        mockMvc.perform(post("/api/v1/accounts/{id}/withdrawals", senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.01,\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void theMakerCannotDecideTheirOwnTransferEvenAsAChecker() throws Exception {
        TestUser makerWhoIsAlsoChecker = testUsers.create(Role.CHECKER, Role.ADMIN);
        RequestPostProcessor maker = testUsers.bearer(makerWhoIsAlsoChecker);
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        long transferId = pendingTransfer(senderId, beneficiaryId, maker);

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(maker))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SELF_APPROVAL_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/approvals/{id}/reject", transferId).with(maker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SELF_APPROVAL_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        expectBalances(senderId, "1000000.00", "750000.00", "250000.00");
        List<AuditLog> rows = auditRows(transferId);
        assertThat(rows).extracting(AuditLog::getAction)
                .containsExactly("TRANSFER", "TRANSFER_APPROVE", "TRANSFER_REJECT");
        assertThat(rows.subList(1, 3)).allSatisfy(row -> {
            assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(row.getErrorCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
            assertThat(row.getActor()).isEqualTo(makerWhoIsAlsoChecker.username());
        });

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(checker()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void approvingPostsTheJournalCapturesTheHoldAndLeavesTheBalancesAsADirectTransferWould() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        TestUser checker = testUsers.create(Role.CHECKER);
        String key = UUID.randomUUID().toString();
        long transferId = pendingTransfer(key, senderId, beneficiaryId);

        String body = mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(testUsers.bearer(checker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.journalId").exists())
                .andExpect(jsonPath("$.decidedBy").value(checker.username()))
                .andExpect(jsonPath("$.decisionReason").doesNotExist())
                .andExpect(jsonPath("$.completedAt").value(NOW.toString()))
                .andReturn().getResponse().getContentAsString();
        long journalId = ((Number) JsonPath.read(body, "$.journalId")).longValue();

        expectBalances(senderId, "750000.00", "750000.00", "0.00");
        expectBalances(beneficiaryId, "250000.00", "250000.00", "0.00");
        assertThat(holdStatus(transferId)).isEqualTo("CAPTURED");
        assertThat(holdTime(transferId, "released_at")).isEqualTo(NOW);
        mockMvc.perform(get("/api/v1/journals/{id}", journalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.createdBy").value(checker.username()))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].accountId").value(senderId))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountId").value(beneficiaryId))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        AuditLog row = auditRows(transferId).getLast();
        assertThat(row.getAction()).isEqualTo("TRANSFER_APPROVE");
        assertThat(row.getActor()).isEqualTo(checker.username());
        assertThat(row.getRoles()).isEqualTo("CHECKER");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(testUsers.bearer(checker)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PENDING"));
        mockMvc.perform(post("/api/v1/approvals/{id}/reject", transferId).with(testUsers.bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PENDING"));

        // The idempotency row keeps the answer the maker got; the current state is on the resource.
        transfer(key, senderId, beneficiaryId, ABOVE_THRESHOLD, "Car")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        expectBalances(senderId, "750000.00", "750000.00", "0.00");
        assertHoldsExplainTheBalanceGap();
    }

    @Test
    void rejectingReleasesTheHoldAndRecordsTheReason() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        TestUser checker = testUsers.create(Role.CHECKER);
        long transferId = pendingTransfer(senderId, beneficiaryId, null);

        mockMvc.perform(post("/api/v1/approvals/{id}/reject", transferId).with(testUsers.bearer(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  Beneficiary not verified  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value(checker.username()))
                .andExpect(jsonPath("$.decisionReason").value("Beneficiary not verified"))
                .andExpect(jsonPath("$.journalId").doesNotExist())
                .andExpect(jsonPath("$.completedAt").value(NOW.toString()));

        expectBalances(senderId, "1000000.00", "1000000.00", "0.00");
        expectBalances(beneficiaryId, "0.00", "0.00", "0.00");
        assertThat(holdStatus(transferId)).isEqualTo("RELEASED");
        assertThat(journalsReferencing(transferId)).isZero();
        AuditLog row = auditRows(transferId).getLast();
        assertThat(row.getAction()).isEqualTo("TRANSFER_REJECT");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(jsonb(row)).contains("\"reason\": \"  Beneficiary not verified  \"");
    }

    @Test
    void aFrozenSenderCannotBeDebitedByAnApprovalAndTheTransferStaysPending() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        long transferId = pendingTransfer(senderId, beneficiaryId, null);
        accountService.freeze(senderId);

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(checker()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        expectBalances(senderId, "1000000.00", "750000.00", "250000.00");
        accountService.unfreeze(senderId);
        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(checker()))
                .andExpect(status().isOk());
    }

    @Test
    void expiryReleasesTheHoldMarksTheTransferExpiredAndIsAuditedAsSystem() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        long transferId = pendingTransfer(senderId, beneficiaryId, null);
        Instant justBeforeExpiry = NOW.plus(approvalProperties.pendingExpiry()).minusSeconds(1);
        Instant afterExpiry = NOW.plus(approvalProperties.pendingExpiry()).plusSeconds(1);

        assertThat(expiryJob.expireDue(justBeforeExpiry)).isZero();
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        assertThat(expiryJob.expireDue(afterExpiry)).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.decidedBy").value("system"))
                .andExpect(jsonPath("$.completedAt").value(afterExpiry.toString()));
        expectBalances(senderId, "1000000.00", "1000000.00", "0.00");
        assertThat(holdStatus(transferId)).isEqualTo("RELEASED");
        assertThat(holdTime(transferId, "released_at")).isEqualTo(afterExpiry);
        AuditLog row = auditRows(transferId).getLast();
        assertThat(row.getAction()).isEqualTo("TRANSFER_EXPIRE");
        assertThat(row.getActor()).isEqualTo("system");
        assertThat(row.getRoles()).isEmpty();
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getClientIp()).isNull();
        assertThat(jsonb(row)).contains("\"transferReference\": \"TRF-").contains("\"amount\": 250000.00");

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(checker()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PENDING"));
        assertThat(expiryJob.expireDue(afterExpiry)).isZero();
        assertHoldsExplainTheBalanceGap();
    }

    @Test
    void theScheduledRunTakesTheShedLockAndExpiresNothingBeforeItsTime() throws Exception {
        long transferId = pendingTransfer(testAccounts.openFundedAccount("1000000.00"), testAccounts.openActiveAccount(),
                null);

        expiryJob.run();

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        String lockedBy = jdbc.queryForObject("select locked_by from shedlock where name = ?", String.class,
                PendingTransferExpiryJob.LOCK_NAME);
        OffsetDateTime lockUntil = jdbc.queryForObject("select lock_until from shedlock where name = ?",
                OffsetDateTime.class, PendingTransferExpiryJob.LOCK_NAME);
        assertThat(lockedBy).isNotBlank();
        assertThat(lockUntil).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
    }

    @Test
    void closingAnAccountWithAnActiveHoldIsRefused() throws Exception {
        long senderId = testAccounts.openFundedAccount("250000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        long transferId = pendingTransfer(senderId, beneficiaryId, null);

        mockMvc.perform(post("/api/v1/accounts/{id}/close", senderId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("FUNDS_ON_HOLD"))
                .andExpect(jsonPath("$.detail").value(containsString("250000.00 INR on hold")));

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId).with(checker())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/accounts/{id}/close", senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    // Both decisions start on a latch; whichever locks the transfer row second must find it decided.
    @Test
    void concurrentApproveAndRejectProduceExactlyOneOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long senderId = testAccounts.openFundedAccount("1000000.00");
                long beneficiaryId = testAccounts.openActiveAccount();
                long transferId = pendingTransfer(senderId, beneficiaryId, null);
                CountDownLatch start = new CountDownLatch(1);
                Future<Object> approve = executor.submit(gated(start, () -> approvalService.approve(transferId)));
                Future<Object> reject = executor.submit(gated(start,
                        () -> approvalService.reject(transferId, new RejectTransferRequest("Raced"))));
                start.countDown();
                Object approveOutcome = approve.get(30, TimeUnit.SECONDS);
                Object rejectOutcome = reject.get(30, TimeUnit.SECONDS);

                List<Object> failures = List.of(approveOutcome, rejectOutcome).stream()
                        .filter(ConflictException.class::isInstance).toList();
                assertThat(failures).hasSize(1);
                assertThat(((ConflictException) failures.getFirst()).getErrorCode())
                        .isEqualTo(TransferErrorCode.TRANSFER_NOT_PENDING);
                boolean approved = !(approveOutcome instanceof ConflictException);
                String status = jdbc.queryForObject("select status from transfer where id = ?", String.class, transferId);
                assertThat(status).isEqualTo(approved ? "COMPLETED" : "REJECTED");
                assertThat(journalsReferencing(transferId)).isEqualTo(approved ? 1 : 0);
                assertThat(holdStatus(transferId)).isEqualTo(approved ? "CAPTURED" : "RELEASED");
                expectBalances(senderId, approved ? "750000.00" : "1000000.00", approved ? "750000.00" : "1000000.00",
                        "0.00");
                expectBalances(beneficiaryId, approved ? "250000.00" : "0.00", approved ? "250000.00" : "0.00", "0.00");
            }
        } finally {
            executor.shutdownNow();
        }
        assertHoldsExplainTheBalanceGap();
    }

    private static Callable<Object> gated(CountDownLatch start, Callable<Object> task) {
        return () -> {
            start.await();
            try {
                return task.call();
            } catch (ConflictException ex) {
                return ex;
            }
        };
    }

    private long pendingTransfer(long senderId, long beneficiaryId, RequestPostProcessor maker) throws Exception {
        return pendingTransfer(UUID.randomUUID().toString(), senderId, beneficiaryId, maker);
    }

    private long pendingTransfer(String key, long senderId, long beneficiaryId) throws Exception {
        return pendingTransfer(key, senderId, beneficiaryId, null);
    }

    private long pendingTransfer(String key, long senderId, long beneficiaryId, RequestPostProcessor maker)
            throws Exception {
        String body = transfer(key, senderId, beneficiaryId, ABOVE_THRESHOLD, "Car", maker)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions transfer(String key, long senderId, long beneficiaryId, String amount, String remarks)
            throws Exception {
        return transfer(key, senderId, beneficiaryId, amount, remarks, null);
    }

    private ResultActions transfer(String key, long senderId, long beneficiaryId, String amount, String remarks,
            RequestPostProcessor maker) throws Exception {
        String json = "{\"fromAccount\":\"" + testAccounts.accountNumber(senderId) + "\",\"toAccount\":\""
                + testAccounts.accountNumber(beneficiaryId) + "\",\"amount\":" + amount + ",\"currency\":\"INR\""
                + (remarks == null ? "" : ",\"remarks\":\"" + remarks + "\"") + "}";
        var request = post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return mockMvc.perform(maker == null ? request : request.with(maker));
    }

    private RequestPostProcessor checker() {
        return testUsers.bearer(testUsers.create(Role.CHECKER));
    }

    private void expectBalances(long accountId, String ledger, String available, String onHold) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ledgerBalance\":" + ledger)))
                .andExpect(content().string(containsString("\"availableBalance\":" + available)))
                .andExpect(content().string(containsString("\"onHold\":" + onHold)))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    private String holdStatus(long transferId) {
        return jdbc.queryForObject("select status from hold where transfer_id = ?", String.class, transferId);
    }

    private Instant holdTime(long transferId, String column) {
        OffsetDateTime time = jdbc.queryForObject("select " + column + " from hold where transfer_id = ?",
                OffsetDateTime.class, transferId);
        return time == null ? null : time.toInstant();
    }

    private int journalsReferencing(long transferId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from transfer t join journal_entry j on j.id = t.journal_entry_id where t.id = ?",
                Integer.class, transferId);
        return count == null ? 0 : count;
    }

    private List<AuditLog> auditRows(long transferId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByIdAsc("TRANSFER", String.valueOf(transferId));
    }

    private String jsonb(AuditLog row) {
        return jdbc.queryForObject("select details::text from audit_log where id = ?", String.class, row.getId());
    }

    // The invariant the holds table and the balance table must agree on, for every account.
    private void assertHoldsExplainTheBalanceGap() {
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                select b.account_id, b.ledger_balance - b.available_balance as gap,
                       coalesce((select sum(h.amount) from hold h where h.account_id = b.account_id
                                 and h.status = 'ACTIVE'), 0) as held
                from account_balance b
                where b.ledger_balance - b.available_balance
                      <> coalesce((select sum(h.amount) from hold h where h.account_id = b.account_id
                                   and h.status = 'ACTIVE'), 0)
                """);
        assertThat(mismatches).isEmpty();
    }
}
