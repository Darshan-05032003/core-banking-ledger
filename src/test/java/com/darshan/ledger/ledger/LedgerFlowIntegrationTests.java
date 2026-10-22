package com.darshan.ledger.ledger;

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
import com.darshan.ledger.account.AccountRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LedgerFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Test
    void depositAndWithdrawalUpdateLedgerAndAvailableBalances() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        String fullNumber = accountRepository.findById(accountId).orElseThrow().getAccountNumber().value();
        expectBalance(accountId, "0.00", "0.00");

        String deposit = cash(accountId, "deposits", "1000.00", "INR", "Opening cash")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journal.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.journal.description").value("Opening cash"))
                .andExpect(jsonPath("$.journal.postedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.journal.createdBy").value("user"))
                .andExpect(jsonPath("$.journal.entries", hasSize(2)))
                .andExpect(jsonPath("$.journal.entries[0].glAccount").value("CASH_VAULT"))
                .andExpect(jsonPath("$.journal.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.journal.entries[1].accountId").value(accountId))
                .andExpect(jsonPath("$.journal.entries[1].direction").value("CREDIT"))
                .andExpect(content().string(containsString("\"amount\":1000.00")))
                .andExpect(content().string(containsString("\"ledgerBalance\":1000.00")))
                .andExpect(content().string(containsString("\"availableBalance\":1000.00")))
                .andExpect(content().string(not(containsString(fullNumber))))
                .andReturn().getResponse().getContentAsString();
        long journalId = ((Number) JsonPath.read(deposit, "$.journal.id")).longValue();
        String reference = JsonPath.read(deposit, "$.journal.reference");
        assertThat(reference).matches("JRN-\\d{10}");

        cash(accountId, "withdrawals", "250.50", "INR", null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/journals/" + (journalId + 1))))
                .andExpect(jsonPath("$.journal.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.journal.description").value("Cash withdrawal"))
                .andExpect(jsonPath("$.journal.entries[0].accountId").value(accountId))
                .andExpect(jsonPath("$.journal.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.journal.entries[1].glAccount").value("CASH_VAULT"))
                .andExpect(jsonPath("$.journal.entries[1].direction").value("CREDIT"))
                .andExpect(content().string(containsString("\"ledgerBalance\":749.50")));
        expectBalance(accountId, "749.50", "749.50");

        mockMvc.perform(get("/api/v1/journals/{id}", journalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.reversalOfId").doesNotExist())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(content().string(not(containsString(fullNumber))));
    }

    @Test
    void withdrawalBeyondTheAvailableBalanceIsRejectedAndChangesNothing() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        cash(accountId, "deposits", "100.00", "INR", null).andExpect(status().isCreated());
        long journalsBefore = journalEntryRepository.count();

        cash(accountId, "withdrawals", "100.01", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Available balance 100.00 INR is lower than 100.01 INR"));

        expectBalance(accountId, "100.00", "100.00");
        assertThat(journalEntryRepository.count()).isEqualTo(journalsBefore);

        cash(accountId, "withdrawals", "100.00", "INR", null).andExpect(status().isCreated());
        expectBalance(accountId, "0.00", "0.00");
    }

    @Test
    void reversalRestoresBalancesAndCanHappenOnlyOnce() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        long depositId = journalIdOf(cash(accountId, "deposits", "500.00", "INR", null));
        expectBalance(accountId, "500.00", "500.00");

        String reversal = reverse(depositId, "Teller keyed the wrong account")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOfId").value(depositId))
                .andExpect(jsonPath("$.description").value(containsString("Teller keyed the wrong account")))
                .andExpect(jsonPath("$.entries[0].glAccount").value("CASH_VAULT"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].accountId").value(accountId))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andReturn().getResponse().getContentAsString();
        long reversalId = ((Number) JsonPath.read(reversal, "$.id")).longValue();
        expectBalance(accountId, "0.00", "0.00");

        reverse(depositId, "Again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("JOURNAL_ALREADY_REVERSED"));
        reverse(reversalId, "Undo the undo")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("JOURNAL_NOT_REVERSIBLE"));
        reverse(999_999L, "Missing")
                .andExpect(status().isNotFound());
        expectBalance(accountId, "0.00", "0.00");
    }

    @Test
    void aDepositTheCustomerAlreadySpentCannotBeReversed() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        long depositId = journalIdOf(cash(accountId, "deposits", "500.00", "INR", null));
        cash(accountId, "withdrawals", "400.00", "INR", null).andExpect(status().isCreated());

        reverse(depositId, "Too late")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));

        expectBalance(accountId, "100.00", "100.00");
        assertThat(journalEntryRepository.existsByReversalOfId(depositId)).isFalse();
    }

    @Test
    void cashOperationsNeedAnActiveAccountInTheRightCurrency() throws Exception {
        long accountId = testAccounts.openActiveAccount();

        cash(accountId, "deposits", "10.00", "USD", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"));
        cash(accountId, "deposits", "10.00", "XYZ", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));

        mockMvc.perform(post("/api/v1/accounts/{id}/freeze", accountId)).andExpect(status().isOk());
        cash(accountId, "deposits", "10.00", "INR", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
        cash(accountId, "withdrawals", "10.00", "INR", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
        mockMvc.perform(post("/api/v1/accounts/{id}/unfreeze", accountId)).andExpect(status().isOk());
        cash(accountId, "deposits", "10.00", "INR", null).andExpect(status().isCreated());

        cash(999_999L, "deposits", "10.00", "INR", null).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", 999_999L)).andExpect(status().isNotFound());
        expectBalance(accountId, "10.00", "10.00");
    }

    @Test
    void anAccountWithMoneyInItCannotBeClosedUntilItIsEmptied() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        cash(accountId, "deposits", "75.00", "INR", null).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts/{id}/close", accountId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_NOT_ZERO"))
                .andExpect(jsonPath("$.detail").value(containsString("75.00 INR")));

        cash(accountId, "withdrawals", "75.00", "INR", null).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts/{id}/close", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        cash(accountId, "deposits", "1.00", "INR", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_CLOSED"));
        expectBalance(accountId, "0.00", "0.00");
    }

    private ResultActions cash(long accountId, String operation, String amount, String currency, String remarks)
            throws Exception {
        String remarksField = remarks == null ? "" : ",\"remarks\":\"" + remarks + "\"";
        return mockMvc.perform(post("/api/v1/accounts/{id}/{op}", accountId, operation)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"" + remarksField + "}"));
    }

    private ResultActions reverse(long journalId, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/journals/{id}/reversal", journalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }

    private void expectBalance(long accountId, String ledger, String available) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(content().string(containsString("\"ledgerBalance\":" + ledger)))
                .andExpect(content().string(containsString("\"availableBalance\":" + available)));
    }

    private static long journalIdOf(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.journal.id")).longValue();
    }
}
