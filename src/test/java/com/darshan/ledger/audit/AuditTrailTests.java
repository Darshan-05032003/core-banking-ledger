package com.darshan.ledger.audit;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.security.Role;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class AuditTrailTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSuccessfulDepositLeavesARowNamingTheTellerTheAccountAndTheAmount() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        TestUser teller = testUsers.create(Role.TELLER);
        String correlationId = correlationId();

        perform(post("/api/v1/accounts/{id}/deposits", accountId)
                .with(testUsers.bearer(teller))
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":250.00,\"currency\":\"INR\",\"remarks\":\"Cash\"}"))
                .andExpect(status().isCreated());

        AuditLog row = single(correlationId);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getActor()).isEqualTo(teller.username());
        assertThat(row.getRoles()).isEqualTo("TELLER");
        assertThat(row.getAction()).isEqualTo("DEPOSIT");
        assertThat(row.getTargetType()).isEqualTo("ACCOUNT");
        assertThat(row.getTargetId()).isEqualTo(String.valueOf(accountId));
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(row.getCorrelationId()).isEqualTo(correlationId);
        assertThat(jsonb(row)).isEqualTo("{\"amount\": 250.00, \"currency\": \"INR\"}");
    }

    @Test
    void aWithdrawalTheRulesRefuseIsRecordedAsAFailureWithItsErrorCode() throws Exception {
        long accountId = testAccounts.openFundedAccount("50.00");
        String correlationId = correlationId();

        perform(post("/api/v1/accounts/{id}/withdrawals", accountId)
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":80.00,\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity());

        AuditLog row = single(correlationId);
        assertThat(row.getAction()).isEqualTo("WITHDRAWAL");
        assertThat(row.getActor()).isEqualTo("user");
        assertThat(row.getRoles()).isEqualTo("ADMIN");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(row.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(row.getTargetId()).isEqualTo(String.valueOf(accountId));
    }

    @Test
    void aCompletedTransferIsRecordedAgainstItsIdWithMaskedAccountNumbers() throws Exception {
        long senderId = testAccounts.openFundedAccount("500.00");
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());
        TestUser customer = testUsers.createCustomer(testAccounts.customerIdOf(senderId));
        String correlationId = correlationId();

        String body = perform(transfer(from, to, "120.00").with(testUsers.bearer(customer))
                .header("X-Correlation-Id", correlationId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(body, "$.id")).longValue();

        AuditLog row = single(correlationId);
        assertThat(row.getAction()).isEqualTo("TRANSFER");
        assertThat(row.getActor()).isEqualTo(customer.username());
        assertThat(row.getRoles()).isEqualTo("CUSTOMER");
        assertThat(row.getTargetType()).isEqualTo("TRANSFER");
        assertThat(row.getTargetId()).isEqualTo(String.valueOf(transferId));
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(jsonb(row))
                .contains("\"fromAccount\": \"XXXXXXXX" + from.substring(8) + "\"")
                .contains("\"toAccount\": \"XXXXXXXX" + to.substring(8) + "\"")
                .contains("\"amount\": 120.00")
                .doesNotContain(from)
                .doesNotContain(to);
    }

    @Test
    void aTransferRejectedByARuleIsRecordedWithTheFailedAttemptsReference() throws Exception {
        long senderId = testAccounts.openFundedAccount("10.00");
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());
        String correlationId = correlationId();

        String body = perform(transfer(from, to, "99.00").header("X-Correlation-Id", correlationId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andReturn().getResponse().getContentAsString();
        long failedTransferId = ((Number) JsonPath.read(body, "$.transferId")).longValue();

        AuditLog row = single(correlationId);
        assertThat(row.getAction()).isEqualTo("TRANSFER");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(row.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(row.getTargetId()).isNull();
        assertThat(jsonb(row))
                .contains("\"transferId\": " + failedTransferId)
                .contains("\"transferReference\": \"TRF-")
                .doesNotContain(from);
    }

    @Test
    void aReplayedTransferIsRecordedWithoutATarget() throws Exception {
        String from = testAccounts.accountNumber(testAccounts.openFundedAccount("500.00"));
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());
        String key = UUID.randomUUID().toString();
        perform(transfer(key, from, to, "10.00")).andExpect(status().isCreated());
        String correlationId = correlationId();

        perform(transfer(key, from, to, "10.00").header("X-Correlation-Id", correlationId))
                .andExpect(status().isCreated());

        AuditLog row = single(correlationId);
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getTargetId()).isNull();
    }

    @Test
    void aDeniedAttemptIsRecordedAgainstTheCallerWhoMadeIt() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        TestUser teller = testUsers.create(Role.TELLER);
        String correlationId = correlationId();

        perform(post("/api/v1/accounts/{id}/freeze", accountId)
                .with(testUsers.bearer(teller))
                .header("X-Correlation-Id", correlationId))
                .andExpect(status().isForbidden());

        AuditLog row = single(correlationId);
        assertThat(row.getActor()).isEqualTo(teller.username());
        assertThat(row.getRoles()).isEqualTo("TELLER");
        assertThat(row.getAction()).isEqualTo("ACCOUNT_FREEZE");
        assertThat(row.getTargetId()).isEqualTo(String.valueOf(accountId));
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(row.getErrorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void kycDecisionsAndUserManagementAreAudited() throws Exception {
        long customerId = testAccounts.customerIdOf(testAccounts.openActiveAccount());
        String rejectCorrelation = correlationId();
        perform(post("/api/v1/customers/{id}/kyc/reject", customerId)
                .header("X-Correlation-Id", rejectCorrelation)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Address proof expired\"}"))
                .andExpect(status().isOk());

        AuditLog reject = single(rejectCorrelation);
        assertThat(reject.getAction()).isEqualTo("KYC_REJECT");
        assertThat(reject.getTargetType()).isEqualTo("CUSTOMER");
        assertThat(reject.getTargetId()).isEqualTo(String.valueOf(customerId));
        assertThat(jsonb(reject)).isEqualTo("{\"reason\": \"Address proof expired\"}");

        String createCorrelation = correlationId();
        String username = "audited-" + UUID.randomUUID().toString().substring(0, 8);
        String body = perform(post("/api/v1/admin/users")
                .header("X-Correlation-Id", createCorrelation)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + TestUsers.PASSWORD
                        + "\",\"roles\":[\"CHECKER\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long userId = ((Number) JsonPath.read(body, "$.id")).longValue();

        AuditLog create = single(createCorrelation);
        assertThat(create.getAction()).isEqualTo("USER_CREATE");
        assertThat(create.getTargetType()).isEqualTo("APP_USER");
        assertThat(create.getTargetId()).isEqualTo(String.valueOf(userId));
        assertThat(jsonb(create))
                .contains("\"username\": \"" + username + "\"")
                .contains("\"roles\": [\"CHECKER\"]")
                .doesNotContain(TestUsers.PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update audit_log set outcome = 'SUCCESS' where id = ?",
            "update audit_log set actor = 'someone-else' where id = ?",
            "delete from audit_log where id = ?"})
    void auditRowsCannotBeChangedOrDeleted(String statement) throws Exception {
        long accountId = testAccounts.openFundedAccount("5.00");
        String correlationId = correlationId();
        perform(post("/api/v1/accounts/{id}/withdrawals", accountId)
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":9.00,\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity());
        long rowId = single(correlationId).getId();

        assertThatThrownBy(() -> jdbc.update(statement, rowId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only")
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23000"));
        assertThat(jdbc.queryForObject("select outcome from audit_log where id = ?", String.class, rowId))
                .isEqualTo("FAILURE");
    }

    @Test
    void theAuditTableRefusesTruncation() {
        assertThatThrownBy(() -> jdbc.execute("truncate table audit_log"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private static MockHttpServletRequestBuilder transfer(String from, String to, String amount) {
        return transfer(UUID.randomUUID().toString(), from, to, amount);
    }

    private static MockHttpServletRequestBuilder transfer(String key, String from, String to, String amount) {
        return post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to + "\",\"amount\":" + amount
                        + ",\"currency\":\"INR\"}");
    }

    private AuditLog single(String correlationId) {
        List<AuditLog> rows = auditLogRepository.findByCorrelationIdOrderByIdAsc(correlationId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // PostgreSQL normalises jsonb spacing, so the stored text is read back through SQL rather
    // than compared with the exact string the writer produced.
    private String jsonb(AuditLog row) {
        return jdbc.queryForObject("select details::text from audit_log where id = ?", String.class, row.getId());
    }

    private static String correlationId() {
        return "audit-" + UUID.randomUUID();
    }
}
