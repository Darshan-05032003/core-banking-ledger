package com.darshan.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.transfer.TransferRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class CustomerOwnershipTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private TransferRepository transferRepository;

    private long ashaAccountId;
    private long ashaCustomerId;
    private long raviAccountId;
    private long raviCustomerId;
    private RequestPostProcessor asha;

    @BeforeEach
    void setUp() {
        ashaAccountId = testAccounts.openFundedAccount("1000.00");
        ashaCustomerId = testAccounts.customerIdOf(ashaAccountId);
        raviAccountId = testAccounts.openFundedAccount("1000.00");
        raviCustomerId = testAccounts.customerIdOf(raviAccountId);
        asha = testUsers.bearer(testUsers.createCustomer(ashaCustomerId));
    }

    @Test
    void aCustomerSeesTheirOwnRecordsAndNobodyElses() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", ashaCustomerId).with(asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ashaCustomerId));
        mockMvc.perform(get("/api/v1/customers/{id}/accounts", ashaCustomerId).with(asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ashaAccountId));
        mockMvc.perform(get("/api/v1/accounts/{id}", ashaAccountId).with(asha))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", ashaAccountId).with(asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(1000.00));

        expectDenied(mockMvc.perform(get("/api/v1/customers/{id}", raviCustomerId).with(asha)));
        expectDenied(mockMvc.perform(get("/api/v1/customers/{id}/accounts", raviCustomerId).with(asha)));
        expectDenied(mockMvc.perform(get("/api/v1/accounts/{id}", raviAccountId).with(asha)));
        expectDenied(mockMvc.perform(get("/api/v1/accounts/{id}/balance", raviAccountId).with(asha)));
        expectDenied(mockMvc.perform(get("/api/v1/accounts/{id}", 999_999L).with(asha)));
    }

    @Test
    void aCustomerCanTransferFromTheirOwnAccountButNotFromSomebodyElses() throws Exception {
        String ashaNumber = testAccounts.accountNumber(ashaAccountId);
        String raviNumber = testAccounts.accountNumber(raviAccountId);

        String body = transfer(asha, ashaNumber, raviNumber)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(body, "$.id")).longValue();
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId).with(asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAccountId").value(ashaAccountId));

        expectDenied(transfer(asha, raviNumber, ashaNumber));

        assertThat(transferRepository.findAll()).filteredOn(t -> t.getFromAccount().getId() == raviAccountId).isEmpty();
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", raviAccountId))
                .andExpect(jsonPath("$.availableBalance").value(1100.00));
    }

    @Test
    void aCustomerCannotReadATransferSentBySomebodyElse() throws Exception {
        String raviTransfer = transfer(null, testAccounts.accountNumber(raviAccountId),
                testAccounts.accountNumber(ashaAccountId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(raviTransfer, "$.id")).longValue();

        expectDenied(mockMvc.perform(get("/api/v1/transfers/{id}", transferId).with(asha)));
    }

    @Test
    void aCustomerLoginWithoutALinkedCustomerOwnsNothing() throws Exception {
        TestUser unlinked = testUsers.createUnlinkedCustomer();
        RequestPostProcessor token = testUsers.bearer(unlinked);

        expectDenied(mockMvc.perform(get("/api/v1/accounts/{id}", ashaAccountId).with(token)));
        expectDenied(mockMvc.perform(get("/api/v1/customers/{id}", ashaCustomerId).with(token)));
    }

    // The mock ADMIN of the shared context sends the transfer when no token is given.
    private ResultActions transfer(RequestPostProcessor caller, String from, String to) throws Exception {
        var request = post("/api/v1/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to
                        + "\",\"amount\":100.00,\"currency\":\"INR\"}");
        return mockMvc.perform(caller == null ? request : request.with(caller));
    }

    private static void expectDenied(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
