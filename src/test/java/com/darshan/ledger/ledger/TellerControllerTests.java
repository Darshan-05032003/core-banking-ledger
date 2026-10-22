package com.darshan.ledger.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.account.AccountBalanceResponse;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TellerController.class)
@WithMockUser(roles = "TELLER")
class TellerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TellerService tellerService;

    @Test
    void depositReturns201WithTheJournalAndTheNewBalance() throws Exception {
        given(tellerService.deposit(10L, new CashRequest(new BigDecimal("500.00"), "INR", "Opening cash")))
                .willReturn(response(JournalType.DEPOSIT, EntryDirection.DEBIT, EntryDirection.CREDIT, "500.00"));

        mockMvc.perform(post("/api/v1/accounts/10/deposits").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500.00,\"currency\":\"INR\",\"remarks\":\"Opening cash\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/journals/31")))
                .andExpect(jsonPath("$.journal.id").value(31))
                .andExpect(jsonPath("$.journal.reference").value("JRN-0000000031"))
                .andExpect(jsonPath("$.journal.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.journal.createdBy").value("teller1"))
                .andExpect(jsonPath("$.journal.entries", hasSize(2)))
                .andExpect(jsonPath("$.journal.entries[0].glAccount").value("CASH_VAULT"))
                .andExpect(jsonPath("$.journal.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.journal.entries[1].maskedAccountNumber").value("XXXXXXXX0014"))
                .andExpect(jsonPath("$.journal.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.balance.accountId").value(10))
                .andExpect(content().string(containsString("\"ledgerBalance\":500.00")))
                .andExpect(content().string(containsString("\"amount\":500.00")));
    }

    @Test
    void withdrawalReturns201AndTheRemainingBalance() throws Exception {
        given(tellerService.withdraw(eq(10L), any()))
                .willReturn(response(JournalType.WITHDRAWAL, EntryDirection.CREDIT, EntryDirection.DEBIT, "120.00"));

        mockMvc.perform(post("/api/v1/accounts/10/withdrawals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":380,\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journal.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.journal.entries[1].direction").value("DEBIT"))
                .andExpect(content().string(containsString("\"availableBalance\":120.00")));
    }

    @Test
    void cashRequestsAreValidatedBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/10/deposits").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-5,\"currency\":\"inr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')]").exists());

        mockMvc.perform(post("/api/v1/accounts/10/withdrawals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.005,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists());

        verifyNoInteractions(tellerService);
    }

    @Test
    void insufficientFundsIs422WithItsErrorCode() throws Exception {
        given(tellerService.withdraw(eq(10L), any())).willThrow(new BusinessRuleException(
                LedgerErrorCode.INSUFFICIENT_FUNDS, "Available balance 50.00 INR is lower than 100.00 INR"));

        mockMvc.perform(post("/api/v1/accounts/10/withdrawals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void depositIntoAFrozenAccountIsAConflict() throws Exception {
        given(tellerService.deposit(eq(10L), any())).willThrow(new ConflictException(
                AccountErrorCode.ACCOUNT_NOT_ACTIVE, "Account XXXXXXXX0014 is FROZEN and cannot be credited"));

        mockMvc.perform(post("/api/v1/accounts/10/deposits").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"currency\":\"INR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
    }

    private static CashOperationResponse response(JournalType type, EntryDirection vaultSide,
            EntryDirection customerSide, String balance) {
        JournalResponse journal = new JournalResponse(31L, "JRN-0000000031", type, "Cash", Instant.parse(
                "2026-10-22T10:15:30Z"), "teller1", null, List.of(
                new LedgerEntryResponse(null, null, GlAccountCode.CASH_VAULT, vaultSide, new BigDecimal("500.00"), "INR"),
                new LedgerEntryResponse(10L, "XXXXXXXX0014", null, customerSide, new BigDecimal("500.00"), "INR")));
        return new CashOperationResponse(journal, new AccountBalanceResponse(10L, "XXXXXXXX0014",
                new BigDecimal(balance), new BigDecimal(balance), BigDecimal.ZERO, "INR"));
    }
}
