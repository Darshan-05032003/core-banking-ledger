package com.darshan.ledger.transfer;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.account.AccountNumber;
import com.darshan.ledger.customer.CustomerService;
import com.darshan.ledger.customer.RejectKycRequest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class TransferFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferRuleChain ruleChain;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aTransferMovesMoneyWritesOneJournalAndCanBeFetched() throws Exception {
        long senderId = testAccounts.openFundedAccount("1000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(beneficiaryId);

        MvcResult result = transfer(UUID.randomUUID().toString(), from, to, "250.50", "INR", "Rent")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/transfers/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fromAccountId").value(senderId))
                .andExpect(jsonPath("$.toAccountId").value(beneficiaryId))
                .andExpect(jsonPath("$.maskedFromAccountNumber").value("XXXXXXXX" + from.substring(8)))
                .andExpect(jsonPath("$.maskedToAccountNumber").value("XXXXXXXX" + to.substring(8)))
                .andExpect(jsonPath("$.remarks").value("Rent"))
                .andExpect(jsonPath("$.createdBy").value("user"))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.failureCode").doesNotExist())
                .andExpect(content().string(containsString("\"amount\":250.50")))
                .andExpect(content().string(not(containsString(from))))
                .andExpect(content().string(not(containsString(to))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(body, "$.id")).longValue();
        long journalId = ((Number) JsonPath.read(body, "$.journalId")).longValue();
        assertThat((String) JsonPath.read(body, "$.reference")).matches("TRF-\\d{10}");
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/transfers/" + transferId);

        expectBalance(senderId, "749.50");
        expectBalance(beneficiaryId, "250.50");

        mockMvc.perform(get("/api/v1/journals/{id}", journalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.description").value(containsString("Rent")))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].accountId").value(senderId))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountId").value(beneficiaryId))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.journalId").value(journalId));
        mockMvc.perform(get("/api/v1/transfers/{id}", 999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void replayingTheSameKeyReturnsTheStoredResponseWithoutASecondTransfer() throws Exception {
        long senderId = testAccounts.openFundedAccount("500.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(beneficiaryId);
        String key = UUID.randomUUID().toString();

        String first = transfer(key, from, to, "100.00", "INR", "Rent")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = transfer(key, from, to, "100", "INR", " Rent ")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));
        expectBalance(senderId, "400.00");
        expectBalance(beneficiaryId, "100.00");
        assertThat(transferRepository.findAll()).filteredOn(t -> t.getFromAccount().getId() == senderId).hasSize(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsRefused() throws Exception {
        long senderId = testAccounts.openFundedAccount("500.00");
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());
        String key = UUID.randomUUID().toString();
        transfer(key, from, to, "100.00", "INR", null).andExpect(status().isCreated());

        transfer(key, from, to, "100.01", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        expectBalance(senderId, "400.00");
    }

    @Test
    void theIdempotencyKeyHeaderIsRequiredAndValidated() throws Exception {
        String from = testAccounts.accountNumber(testAccounts.openFundedAccount("50.00"));
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());

        transfer(null, from, to, "10.00", "INR", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        transfer("not valid because of spaces", from, to, "10.00", "INR", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void aRejectedTransferIsRecordedAsFailedAndReplayedAsTheSameProblem() throws Exception {
        long senderId = testAccounts.openFundedAccount("50.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(beneficiaryId);
        String key = UUID.randomUUID().toString();

        String problem = transfer(key, from, to, "80.00", "INR", "Too much")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Available balance 50.00 INR is lower than 80.00 INR"))
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.transferReference").value(containsString("TRF-")))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) JsonPath.read(problem, "$.transferId")).longValue();

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.failureReason").value(containsString("lower than 80.00 INR")))
                .andExpect(jsonPath("$.remarks").value("Too much"))
                .andExpect(jsonPath("$.journalId").doesNotExist())
                .andExpect(content().string(containsString("\"amount\":80.00")));

        transfer(key, from, to, "80.00", "INR", "Too much")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transferId").value(transferId))
                .andExpect(jsonPath("$.correlationId").value((String) JsonPath.read(problem, "$.correlationId")));

        expectBalance(senderId, "50.00");
        expectBalance(beneficiaryId, "0.00");
    }

    @Test
    void aRequestNamingAnUnknownOrMistypedAccountLeavesNoTransferBehind() throws Exception {
        long senderId = testAccounts.openFundedAccount("50.00");
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(testAccounts.openActiveAccount());
        long before = transferRepository.count();

        String unknown = AccountNumber.of("0001", 9_999_999L).value();
        transfer(UUID.randomUUID().toString(), from, unknown, "10.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value(containsString("XXXXXXXX" + unknown.substring(8))))
                .andExpect(jsonPath("$.detail").value(not(containsString(unknown))))
                .andExpect(jsonPath("$.transferId").doesNotExist());
        String mistyped = to.substring(0, 11) + (to.charAt(11) == '9' ? '0' : (char) (to.charAt(11) + 1));
        transfer(UUID.randomUUID().toString(), from, mistyped, "10.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_NUMBER"));
        transfer(UUID.randomUUID().toString(), from, from, "10.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SAME_ACCOUNT"));
        transfer(UUID.randomUUID().toString(), from, to, "10.00", "XYZ", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));

        assertThat(transferRepository.count()).isEqualTo(before);
        expectBalance(senderId, "50.00");
    }

    @Test
    void everyRuleCanRejectATransferThroughTheApi() throws Exception {
        long senderId = testAccounts.openFundedAccount("3000000.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        long dollarAccountId = testAccounts.openActiveAccount("USD");
        String from = testAccounts.accountNumber(senderId);
        String to = testAccounts.accountNumber(beneficiaryId);

        mockMvc.perform(post("/api/v1/accounts/{id}/freeze", beneficiaryId)).andExpect(status().isOk());
        transfer(UUID.randomUUID().toString(), from, to, "10.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.detail").value(containsString("Beneficiary account XXXXXXXX" + to.substring(8))));
        mockMvc.perform(post("/api/v1/accounts/{id}/unfreeze", beneficiaryId)).andExpect(status().isOk());

        transfer(UUID.randomUUID().toString(), from, testAccounts.accountNumber(dollarAccountId), "10.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"));
        transfer(UUID.randomUUID().toString(), from, to, "10.00", "USD", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"));

        transfer(UUID.randomUUID().toString(), from, to, "500000.01", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PER_TRANSACTION_LIMIT_EXCEEDED"));

        for (int i = 0; i < 4; i++) {
            transfer(UUID.randomUUID().toString(), from, to, "500000.00", "INR", null).andExpect(status().isCreated());
        }
        transfer(UUID.randomUUID().toString(), from, to, "0.01", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(containsString("2000000.00 INR plus 0.01 INR")));
        expectBalance(senderId, "1000000.00");
        expectBalance(beneficiaryId, "2000000.00");

        customerService.rejectKyc(testAccounts.customerIdOf(senderId), new RejectKycRequest("Document expired"));
        transfer(UUID.randomUUID().toString(), from, to, "1.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_VERIFIED"));
    }

    @Test
    void theRulesRunInTheDocumentedOrder() {
        List<Class<?>> order = ruleChain.rules().stream().<Class<?>>map(TransferRule::getClass).toList();

        assertThat(order).containsExactly(AccountsExistRule.class, DistinctAccountsRule.class,
                AccountsActiveRule.class, SameCurrencyRule.class, SenderKycVerifiedRule.class,
                AmountWithinLimitRule.class, DailyLimitRule.class, SufficientBalanceRule.class);
    }

    private ResultActions transfer(String key, String from, String to, String amount, String currency, String remarks)
            throws Exception {
        String remarksField = remarks == null ? "" : ",\"remarks\":\"" + remarks + "\"";
        var request = post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to + "\",\"amount\":" + amount
                        + ",\"currency\":\"" + currency + "\"" + remarksField + "}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private void expectBalance(long accountId, String balance) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ledgerBalance\":" + balance)))
                .andExpect(content().string(containsString("\"availableBalance\":" + balance)));
    }
}
