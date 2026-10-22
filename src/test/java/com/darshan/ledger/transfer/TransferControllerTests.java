package com.darshan.ledger.transfer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.InvalidRequestException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.common.idempotency.IdempotencyErrorCode;
import com.darshan.ledger.common.idempotency.StoredResponse;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransferController.class)
@WithMockUser(roles = "CUSTOMER")
class TransferControllerTests {

    private static final String BODY = "{\"fromAccount\":\"000100000023\",\"toAccount\":\"000100000057\","
            + "\"amount\":250.00,\"currency\":\"INR\",\"remarks\":\"Rent\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private TransferRateLimiter rateLimiter;

    @Test
    void aTransferWaitingForApprovalReturns202WithItsLocation() throws Exception {
        given(transferService.transfer(eq("order-1"), any())).willReturn(new TransferOutcome.PendingApproval(
                new TransferResponse(12L, "TRF-0000000012", TransferStatus.PENDING_APPROVAL, 23L, "XXXXXXXX0023",
                        57L, "XXXXXXXX0057", new BigDecimal("250000.00"), "INR", "Rent", null, null, null, null,
                        "user", Instant.parse("2026-10-22T10:15:30Z"), null, null, null)));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/v1/transfers/12")))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.journalId").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    @Test
    void aNewTransferReturns201WithItsLocationAndBody() throws Exception {
        TransferRequest request = new TransferRequest("000100000023", "000100000057", new BigDecimal("250.00"), "INR",
                "Rent");
        given(transferService.transfer("order-1", request)).willReturn(new TransferOutcome.Created(response()));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/transfers/12")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.reference").value("TRF-0000000012"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.maskedFromAccountNumber").value("XXXXXXXX0023"))
                .andExpect(jsonPath("$.maskedToAccountNumber").value("XXXXXXXX0057"))
                .andExpect(jsonPath("$.journalReference").value("JRN-0000000040"))
                .andExpect(content().string(containsString("\"amount\":250.00")))
                .andExpect(content().string(containsString("\"currency\":\"INR\"")));
    }

    @Test
    void aReplayedTransferReturnsTheStoredBodyWithTheOriginalStatus() throws Exception {
        given(transferService.transfer(eq("order-1"), any()))
                .willReturn(new TransferOutcome.Replayed(new StoredResponse(201, "{\"id\":12,\"status\":\"COMPLETED\"}")));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"id\":12,\"status\":\"COMPLETED\"}"));
    }

    @Test
    void aReplayedRejectionComesBackAsTheStoredProblem() throws Exception {
        given(transferService.transfer(eq("order-1"), any())).willReturn(new TransferOutcome.Replayed(
                new StoredResponse(422, "{\"status\":422,\"errorCode\":\"INSUFFICIENT_FUNDS\"}")));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void aMissingIdempotencyKeyIs400WithItsErrorCode() throws Exception {
        given(transferService.transfer(isNull(), any())).willThrow(new InvalidRequestException(
                IdempotencyErrorCode.IDEMPOTENCY_KEY_REQUIRED, "The Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("The Idempotency-Key header is required"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aRejectedTransferCarriesItsReferenceInTheProblemBody() throws Exception {
        TransferRejectedException rejection = TransferRejectedException.byRule(
                new TransferRuleResult.Rejected(LedgerErrorCode.INSUFFICIENT_FUNDS,
                        "Available balance 50.00 INR is lower than 250.00 INR"),
                new TransferAttempt(23L, 57L, Money.of("250.00", Currency.getInstance("INR")), "Rent"));
        Transfer failed = mock(Transfer.class);
        given(failed.getId()).willReturn(13L);
        given(failed.getReference()).willReturn("TRF-0000000013");
        TransferRejectedException recorded = rejection.recordedAs(failed);
        given(transferService.transfer(eq("order-2"), any())).willThrow(recorded);

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Available balance 50.00 INR is lower than 250.00 INR"))
                .andExpect(jsonPath("$.transferId").value(13))
                .andExpect(jsonPath("$.transferReference").value("TRF-0000000013"));
    }

    @Test
    void aReusedKeyWithAnotherPayloadIs422() throws Exception {
        given(transferService.transfer(eq("order-1"), any())).willThrow(new BusinessRuleException(
                IdempotencyErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key 'order-1' was already used"));

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void requestsAreValidatedBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccount\":\"12345\",\"toAccount\":\"\",\"amount\":-1,\"currency\":\"inr\","
                                + "\"remarks\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'fromAccount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'toAccount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'remarks')]").exists());

        mockMvc.perform(post("/api/v1/transfers").with(csrf())
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccount\":\"000100000023\",\"toAccount\":\"000100000057\",\"amount\":10.005,"
                                + "\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists());

        verifyNoInteractions(transferService);
    }

    @Test
    void aTransferCanBeFetchedById() throws Exception {
        given(transferService.get(12L)).willReturn(response());

        mockMvc.perform(get("/api/v1/transfers/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("TRF-0000000012"))
                .andExpect(jsonPath("$.fromAccountId").value(23))
                .andExpect(jsonPath("$.toAccountId").value(57))
                .andExpect(jsonPath("$.failureCode").doesNotExist());
    }

    @Test
    void anUnknownTransferIs404() throws Exception {
        given(transferService.get(99L)).willThrow(new ResourceNotFoundException("Transfer", 99L));

        mockMvc.perform(get("/api/v1/transfers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    private static TransferResponse response() {
        Instant now = Instant.parse("2026-10-22T10:15:30Z");
        return new TransferResponse(12L, "TRF-0000000012", TransferStatus.COMPLETED, 23L, "XXXXXXXX0023", 57L,
                "XXXXXXXX0057", new BigDecimal("250.00"), "INR", "Rent", 40L, "JRN-0000000040", null, null, "user",
                now, now, null, null);
    }
}
