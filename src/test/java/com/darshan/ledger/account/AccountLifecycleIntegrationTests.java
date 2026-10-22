package com.darshan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static com.darshan.ledger.FixedClockConfiguration.TODAY;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.customer.KycStatus;
import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AccountLifecycleIntegrationTests {

    private static final AtomicInteger EMAIL_SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void customerGoesThroughOnboardingKycAndTheFullAccountLifecycle() throws Exception {
        String email = uniqueEmail();
        long customerId = idOf(onboard("Asha Rao", email, "+919876543210", "1990-05-17")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.maskedEmail").value("a***@example.com"))
                .andExpect(jsonPath("$.maskedPhone").value("XXXXXXXXX3210"))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(content().string(not(containsString(email))))
                .andReturn().getResponse().getContentAsString());

        openAccount(customerId, "SAVINGS", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_VERIFIED"));

        mockMvc.perform(post("/api/v1/customers/{id}/kyc/verify", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.kycDecidedAt").value(NOW.toString()));

        String savings = openAccount(customerId, "SAVINGS", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.type").value("SAVINGS"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.openedOn").value(TODAY.toString()))
                .andExpect(jsonPath("$.closedOn").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long savingsId = idOf(savings);
        String maskedSavingsNumber = JsonPath.read(savings, "$.maskedAccountNumber");

        long currentId = idOf(openAccount(customerId, "CURRENT", "INR")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        Account savingsAccount = accountRepository.findById(savingsId).orElseThrow();
        Account currentAccount = accountRepository.findById(currentId).orElseThrow();
        assertThat(savingsAccount.getAccountNumber().value()).hasSize(12).startsWith("0001");
        assertThat(savingsAccount.getAccountNumber().masked()).isEqualTo(maskedSavingsNumber);
        assertThat(savings).doesNotContain(savingsAccount.getAccountNumber().value());
        assertThat(currentAccount.getAccountNumber()).isNotEqualTo(savingsAccount.getAccountNumber());
        assertThat(Long.parseLong(currentAccount.getAccountNumber().value().substring(4, 11)))
                .isGreaterThan(Long.parseLong(savingsAccount.getAccountNumber().value().substring(4, 11)));
        assertThat(savingsAccount.getCreatedAt()).isEqualTo(NOW);

        mockMvc.perform(get("/api/v1/customers/{id}/accounts", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(savingsId))
                .andExpect(jsonPath("$[1].id").value(currentId));

        transition(savingsId, "freeze").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        transition(savingsId, "freeze").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
        transition(savingsId, "unfreeze").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        transition(savingsId, "unfreeze").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FROZEN"));

        transition(savingsId, "close").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedOn").value(TODAY.toString()));
        transition(savingsId, "close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_CLOSED"));
        transition(savingsId, "freeze").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_CLOSED"));

        mockMvc.perform(get("/api/v1/accounts/{id}", savingsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.maskedAccountNumber").value(maskedSavingsNumber));
    }

    @Test
    void rejectedKycBlocksNewAccountsAndRecordsTheReason() throws Exception {
        long customerId = onboardVerified();

        mockMvc.perform(post("/api/v1/customers/{id}/kyc/reject", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Address proof expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value(KycStatus.REJECTED.name()))
                .andExpect(jsonPath("$.kycReason").value("Address proof expired"));

        mockMvc.perform(post("/api/v1/customers/{id}/kyc/reject", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KYC_STATUS_UNCHANGED"));

        openAccount(customerId, "CURRENT", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_VERIFIED"));
    }

    @Test
    void emailsAreUniqueIgnoringCase() throws Exception {
        String email = uniqueEmail();
        onboard("Ravi Kumar", email, "9876501234", "1985-01-01").andExpect(status().isCreated());

        onboard("Ravi Kumar", email.toUpperCase(), "9876501234", "1985-01-01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void minimumAgeIsJudgedAgainstTheApplicationClock() throws Exception {
        onboard("Too Young", uniqueEmail(), "9876501234", TODAY.minusYears(18).plusDays(1).toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_UNDERAGE"));

        onboard("Just Adult", uniqueEmail(), "9876501234", TODAY.minusYears(18).toString())
                .andExpect(status().isCreated());
    }

    @Test
    void unknownCurrencyIsRejectedWithItsOwnErrorCode() throws Exception {
        long customerId = onboardVerified();

        openAccount(customerId, "SAVINGS", "XYZ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));
    }

    private long onboardVerified() throws Exception {
        long customerId = idOf(onboard("Meera Shah", uniqueEmail(), "9876501234", "1992-02-02")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/customers/{id}/kyc/verify", customerId))
                .andExpect(status().isOk());
        return customerId;
    }

    private ResultActions onboard(String name, String email, String phone, String dateOfBirth) throws Exception {
        return mockMvc.perform(post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"%s","email":"%s","phone":"%s","dateOfBirth":"%s"}
                        """.formatted(name, email, phone, dateOfBirth)));
    }

    private ResultActions openAccount(long customerId, String type, String currency) throws Exception {
        String currencyField = currency == null ? "" : ",\"currency\":\"" + currency + "\"";
        return mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":" + customerId + ",\"type\":\"" + type + "\"" + currencyField + "}"));
    }

    private ResultActions transition(long accountId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/{id}/{action}", accountId, action));
    }

    private static long idOf(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static String uniqueEmail() {
        return "asha" + EMAIL_SEQUENCE.incrementAndGet() + "@example.com";
    }
}
