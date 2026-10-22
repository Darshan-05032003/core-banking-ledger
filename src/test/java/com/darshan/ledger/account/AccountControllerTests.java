package com.darshan.ledger.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@WithMockUser(roles = "ADMIN")
class AccountControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void openingReturns201WithLocationAndAMaskedAccountNumber() throws Exception {
        given(accountService.open(new OpenAccountRequest(7L, AccountType.SAVINGS, null)))
                .willReturn(response(AccountStatus.ACTIVE, null));

        mockMvc.perform(post("/api/v1/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":7,\"type\":\"SAVINGS\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/accounts/10")))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.maskedAccountNumber").value("XXXXXXXX0014"))
                .andExpect(jsonPath("$.customerId").value(7))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.openedOn").value("2026-10-22"))
                .andExpect(content().string(not(containsString("000100000014"))));
    }

    @Test
    void openingValidatesTheRequest() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"inr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'type')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')].message")
                        .value("must be a three-letter ISO 4217 code"));

        verifyNoInteractions(accountService);
    }

    @Test
    void unknownAccountTypeIsAMalformedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":7,\"type\":\"LOAN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void openingForAnUnverifiedCustomerIs422() throws Exception {
        given(accountService.open(any())).willThrow(new BusinessRuleException(
                AccountErrorCode.CUSTOMER_NOT_VERIFIED, "Customer 7 must be KYC verified before an account can be opened"));

        mockMvc.perform(post("/api/v1/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":7,\"type\":\"CURRENT\",\"currency\":\"INR\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_VERIFIED"));
    }

    @Test
    void listsTheAccountsOfACustomer() throws Exception {
        given(accountService.listForCustomer(7L)).willReturn(List.of(response(AccountStatus.ACTIVE, null)));

        mockMvc.perform(get("/api/v1/customers/7/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].maskedAccountNumber").value("XXXXXXXX0014"));
    }

    @Test
    void returnsTheBalanceWithTwoDecimalAmounts() throws Exception {
        given(accountService.balance(10L)).willReturn(new AccountBalanceResponse(
                10L, "XXXXXXXX0014", new BigDecimal("1250.00"), new BigDecimal("1000.50"), "INR"));

        mockMvc.perform(get("/api/v1/accounts/10/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(10))
                .andExpect(jsonPath("$.maskedAccountNumber").value("XXXXXXXX0014"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(content().string(containsString("\"ledgerBalance\":1250.00")))
                .andExpect(content().string(containsString("\"availableBalance\":1000.50")));
    }

    @Test
    void freezeUnfreezeAndCloseDelegateToTheService() throws Exception {
        given(accountService.freeze(10L)).willReturn(response(AccountStatus.FROZEN, null));
        given(accountService.unfreeze(10L)).willReturn(response(AccountStatus.ACTIVE, null));
        given(accountService.close(10L)).willReturn(response(AccountStatus.CLOSED, LocalDate.of(2026, 10, 23)));

        mockMvc.perform(post("/api/v1/accounts/10/freeze").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mockMvc.perform(post("/api/v1/accounts/10/unfreeze").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/accounts/10/close").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedOn").value("2026-10-23"));

        verify(accountService).freeze(10L);
        verify(accountService).unfreeze(10L);
        verify(accountService).close(10L);
    }

    @Test
    void invalidStateTransitionIsAConflict() throws Exception {
        given(accountService.freeze(10L)).willThrow(new ConflictException(
                AccountErrorCode.ACCOUNT_NOT_ACTIVE, "Account XXXXXXXX0014 is FROZEN and cannot be frozen"));

        mockMvc.perform(post("/api/v1/accounts/10/freeze").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void closingWithABalanceIs422() throws Exception {
        given(accountService.close(10L)).willThrow(new BusinessRuleException(
                AccountErrorCode.BALANCE_NOT_ZERO, "Account balance is 12.50 INR; it must be exactly zero before closing"));

        mockMvc.perform(post("/api/v1/accounts/10/close").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_NOT_ZERO"));
    }

    private static AccountResponse response(AccountStatus status, LocalDate closedOn) {
        return new AccountResponse(10L, "XXXXXXXX0014", 7L, AccountType.SAVINGS, "INR", status,
                LocalDate.of(2026, 10, 22), closedOn);
    }
}
