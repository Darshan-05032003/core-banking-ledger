package com.darshan.ledger.statement;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static com.darshan.ledger.FixedClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.MutableClock;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.audit.AuditLog;
import com.darshan.ledger.audit.AuditLogRepository;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.ledger.CashRequest;
import com.darshan.ledger.ledger.EntryDirection;
import com.darshan.ledger.ledger.GlAccountCode;
import com.darshan.ledger.ledger.JournalType;
import com.darshan.ledger.ledger.TellerService;
import com.darshan.ledger.transfer.TransferRequest;
import com.darshan.ledger.transfer.TransferService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class StatementIntegrationTests {

    private static final String STATEMENT = "/api/v1/accounts/{id}/statement";
    private static final String STATEMENT_CSV = "/api/v1/accounts/{id}/statement.csv";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private TellerService tellerService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void runningBalanceAcrossPagesMatchesAnInMemoryComputation() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        long otherId = testAccounts.openFundedAccount("50000.00");
        Random random = new Random(20261022L);
        List<BigDecimal> expectedRunning = new ArrayList<>();
        BigDecimal balance = BigDecimal.ZERO.setScale(2);

        for (int i = 0; i < 23; i++) {
            BigDecimal amount = BigDecimal.valueOf(random.nextInt(100, 20_000), 2);
            boolean canPay = balance.compareTo(amount) >= 0;
            switch (i % 4) {
                case 0 -> deposit(accountId, amount, "Deposit " + i);
                case 1 -> {
                    if (canPay) {
                        withdraw(accountId, amount);
                        amount = amount.negate();
                    } else {
                        deposit(accountId, amount, "Deposit instead " + i);
                    }
                }
                case 2 -> {
                    if (canPay) {
                        transfer(accountId, otherId, amount);
                        amount = amount.negate();
                    } else {
                        transfer(otherId, accountId, amount);
                    }
                }
                default -> transfer(otherId, accountId, amount);
            }
            balance = balance.add(amount);
            expectedRunning.add(balance);
        }

        List<StatementLine> lines = allPages(accountId, TODAY, TODAY, 7, null);

        assertThat(lines).hasSize(23);
        for (int i = 0; i < lines.size(); i++) {
            assertThat(lines.get(i).runningBalance()).as("running balance after line %d", i)
                    .isEqualByComparingTo(expectedRunning.get(i));
        }
        assertThat(lines).extracting(StatementLine::postedAt).containsOnly(NOW);
        assertThat(lines).extracting(StatementLine::entryId).isSorted();
        StatementResponse firstPage = page(accountId, TODAY, TODAY, null, 7, null);
        assertThat(firstPage.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(firstPage.closingBalance()).isEqualByComparingTo(balance);
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(jsonPath("$.ledgerBalance").value(balance.doubleValue()));
    }

    @Test
    void openingAndClosingBalancesAreComputedForAPeriodThatStartsMidHistory() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        deposit(accountId, new BigDecimal("1000.00"), "Day one");
        withdraw(accountId, new BigDecimal("250.00"));
        clock.advance(Duration.ofDays(2));
        deposit(accountId, new BigDecimal("80.00"), "Day three");
        withdraw(accountId, new BigDecimal("30.00"));
        LocalDate dayThree = TODAY.plusDays(2);

        StatementResponse dayThreeOnly = page(accountId, dayThree, dayThree, null, 50, null);
        assertThat(dayThreeOnly.openingBalance()).isEqualByComparingTo("750.00");
        assertThat(dayThreeOnly.closingBalance()).isEqualByComparingTo("800.00");
        assertThat(dayThreeOnly.lines()).extracting(StatementLine::description)
                .containsExactly("Day three", "Cash withdrawal");
        assertThat(dayThreeOnly.lines()).extracting(StatementLine::runningBalance)
                .containsExactly(new BigDecimal("830.00"), new BigDecimal("800.00"));
        assertThat(dayThreeOnly.nextCursor()).isNull();

        StatementResponse quietDay = page(accountId, TODAY.plusDays(1), TODAY.plusDays(1), null, 50, null);
        assertThat(quietDay.openingBalance()).isEqualByComparingTo("750.00");
        assertThat(quietDay.closingBalance()).isEqualByComparingTo("750.00");
        assertThat(quietDay.lines()).isEmpty();
        assertThat(quietDay.nextCursor()).isNull();

        StatementResponse everything = page(accountId, TODAY, dayThree, null, 50, null);
        assertThat(everything.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(everything.closingBalance()).isEqualByComparingTo("800.00");
        assertThat(everything.lines()).hasSize(4);
    }

    @Test
    void entriesPostedAfterTheFirstPageDoNotShiftOrDuplicateTheFollowingPages() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        for (int i = 1; i <= 5; i++) {
            deposit(accountId, new BigDecimal("10.00"), "Early " + i);
        }
        StatementResponse first = page(accountId, TODAY, TODAY, null, 2, null);
        assertThat(first.lines()).extracting(StatementLine::description).containsExactly("Early 1", "Early 2");
        assertThat(first.nextCursor()).isNotNull();

        clock.advance(Duration.ofHours(1));
        for (int i = 1; i <= 3; i++) {
            deposit(accountId, new BigDecimal("10.00"), "Late " + i);
        }

        List<StatementLine> rest = new ArrayList<>();
        String cursor = first.nextCursor();
        while (cursor != null) {
            StatementResponse next = page(accountId, TODAY, TODAY, cursor, 2, null);
            rest.addAll(next.lines());
            cursor = next.nextCursor();
        }
        assertThat(rest).extracting(StatementLine::description)
                .containsExactly("Early 3", "Early 4", "Early 5", "Late 1", "Late 2", "Late 3");
        assertThat(rest.getFirst().runningBalance())
                .isEqualByComparingTo(first.lines().getLast().runningBalance().add(new BigDecimal("10.00")));
        assertThat(rest.getLast().runningBalance()).isEqualByComparingTo("80.00");
        assertThat(rest).extracting(StatementLine::postedAt)
                .containsExactly(NOW, NOW, NOW, NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                        NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void theCounterpartyIsTheVaultForCashAndAMaskedAccountForTransfers() throws Exception {
        long accountId = testAccounts.openFundedAccount("300.00");
        long otherId = testAccounts.openActiveAccount();
        String otherNumber = testAccounts.accountNumber(otherId);
        transfer(accountId, otherId, new BigDecimal("120.00"));

        MvcResult result = mockMvc.perform(statement(accountId, TODAY, TODAY).param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        StatementResponse response = objectMapper.readValue(body, StatementResponse.class);

        assertThat(body).doesNotContain(otherNumber).doesNotContain(testAccounts.accountNumber(accountId));
        assertThat(response.maskedAccountNumber()).isEqualTo("XXXXXXXX" + testAccounts.accountNumber(accountId).substring(8));
        StatementLine funding = response.lines().getFirst();
        assertThat(funding.journalType()).isEqualTo(JournalType.DEPOSIT);
        assertThat(funding.direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(funding.counterparty().glAccount()).isEqualTo(GlAccountCode.CASH_VAULT);
        assertThat(funding.counterparty().maskedAccountNumber()).isNull();
        StatementLine sent = response.lines().get(1);
        assertThat(sent.journalType()).isEqualTo(JournalType.TRANSFER);
        assertThat(sent.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(sent.journalReference()).startsWith("JRN-");
        assertThat(sent.counterparty().maskedAccountNumber()).isEqualTo("XXXXXXXX" + otherNumber.substring(8));
        assertThat(sent.counterparty().glAccount()).isNull();
        assertThat(sent.runningBalance()).isEqualByComparingTo("180.00");
    }

    @Test
    void theLastPageHasNoCursorAndPageSizesAreBounded() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        for (int i = 0; i < 4; i++) {
            deposit(accountId, new BigDecimal("1.00"), "Line " + i);
        }

        StatementResponse first = page(accountId, TODAY, TODAY, null, 2, null);
        StatementResponse second = page(accountId, TODAY, TODAY, first.nextCursor(), 2, null);
        assertThat(second.lines()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        StatementResponse exact = page(accountId, TODAY, TODAY, null, 4, null);
        assertThat(exact.nextCursor()).isNull();

        mockMvc.perform(statement(accountId, TODAY, TODAY).param("size", "200")).andExpect(status().isOk());
        mockMvc.perform(statement(accountId, TODAY, TODAY).param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
        mockMvc.perform(statement(accountId, TODAY, TODAY).param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(statement(accountId, TODAY, TODAY).param("size", "x")).andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidCursors() {
        return Stream.of(
                Arguments.of("not base64 at all!"),
                Arguments.of(Base64.getUrlEncoder().withoutPadding().encodeToString("hello".getBytes())),
                Arguments.of(Base64.getUrlEncoder().withoutPadding().encodeToString("yesterday|12".getBytes())),
                Arguments.of(Base64.getUrlEncoder().withoutPadding().encodeToString("2026-10-22T10:15:30Z|abc".getBytes())));
    }

    @ParameterizedTest
    @MethodSource("invalidCursors")
    void aCursorThatDidNotComeFromAPreviousPageIs400(String cursor) throws Exception {
        long accountId = testAccounts.openActiveAccount();

        mockMvc.perform(statement(accountId, TODAY, TODAY).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void thePeriodMustBeOrderedAndNoLongerThanTheConfiguredMaximum() throws Exception {
        long accountId = testAccounts.openActiveAccount();

        mockMvc.perform(statement(accountId, TODAY, TODAY.minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PERIOD"));
        mockMvc.perform(statement(accountId, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PERIOD_TOO_LONG"))
                .andExpect(jsonPath("$.detail").value("A statement from 2025-01-01 may end no later than 2025-12-31"));
        mockMvc.perform(statement(accountId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
                .andExpect(status().isOk());
        mockMvc.perform(get(STATEMENT, accountId).param("from", TODAY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(STATEMENT, accountId).param("from", "22/10/2026").param("to", TODAY.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(statement(999_999L, TODAY, TODAY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theCsvCarriesAHeaderLineAndOneQuotedRowPerLedgerLine() throws Exception {
        long accountId = testAccounts.openFundedAccount("100.00");
        long otherId = testAccounts.openActiveAccount();
        String otherNumber = testAccounts.accountNumber(otherId);
        withdraw(accountId, new BigDecimal("40.50"), "Rent, \"October\"");
        transfer(accountId, otherId, new BigDecimal("10.25"));
        deposit(accountId, new BigDecimal("5.00"), "=SUM(A1:A9)");

        MvcResult started = mockMvc.perform(csv(accountId, TODAY, TODAY))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult result = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"statement-XXXXXXXX"
                        + testAccounts.accountNumber(accountId).substring(8) + "-" + TODAY + "-" + TODAY + ".csv\""))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> rows = List.of(body.split("\r\n"));

        assertThat(body).endsWith("\r\n").doesNotContain(otherNumber)
                .doesNotContain(testAccounts.accountNumber(accountId));
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).isEqualTo(StatementCsvWriter.HEADER);
        assertThat(rows.get(1)).matches("2026-10-22T10:15:30Z,JRN-\\d{10},DEPOSIT,Test funding,CASH_VAULT,,100.00,100.00,INR");
        assertThat(rows.get(2)).matches("2026-10-22T10:15:30Z,JRN-\\d{10},WITHDRAWAL,\"Rent, \"\"October\"\"\",CASH_VAULT,40.50,,59.50,INR");
        assertThat(rows.get(3)).matches("2026-10-22T10:15:30Z,JRN-\\d{10},TRANSFER,Transfer TRF-\\d{10},XXXXXXXX"
                + otherNumber.substring(8) + ",10.25,,49.25,INR");
        assertThat(rows.get(4)).matches("2026-10-22T10:15:30Z,JRN-\\d{10},DEPOSIT,'=SUM\\(A1:A9\\),CASH_VAULT,,5.00,54.25,INR");
    }

    @Test
    void csvRequestsThatCannotBeServedFailBeforeAnyRowIsWritten() throws Exception {
        long accountId = testAccounts.openActiveAccount();

        mockMvc.perform(csv(999_999L, TODAY, TODAY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(csv(accountId, TODAY, TODAY.minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PERIOD"));
    }

    @Test
    void aCustomerReadsAndDownloadsOnlyTheirOwnStatement() throws Exception {
        long ownId = testAccounts.openFundedAccount("10.00");
        long otherId = testAccounts.openFundedAccount("10.00");
        RequestPostProcessor customer = testUsers.bearer(testUsers.createCustomer(testAccounts.customerIdOf(ownId)));

        mockMvc.perform(statement(ownId, TODAY, TODAY).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].amount").value(10.00));
        mockMvc.perform(csv(ownId, TODAY, TODAY).with(customer))
                .andExpect(request().asyncStarted());

        expectDenied(mockMvc.perform(statement(otherId, TODAY, TODAY).with(customer)));
        expectDenied(mockMvc.perform(csv(otherId, TODAY, TODAY).with(customer)));
    }

    @Test
    void everyStatementViewAndDownloadIsAudited() throws Exception {
        long accountId = testAccounts.openFundedAccount("10.00");
        long otherId = testAccounts.openFundedAccount("10.00");
        RequestPostProcessor customer = testUsers.bearer(testUsers.createCustomer(testAccounts.customerIdOf(accountId)));

        String viewed = UUID.randomUUID().toString();
        mockMvc.perform(statement(accountId, TODAY.minusDays(3), TODAY).with(customer).header("X-Correlation-Id", viewed))
                .andExpect(status().isOk());
        String downloaded = UUID.randomUUID().toString();
        mockMvc.perform(csv(accountId, TODAY, TODAY).with(customer).header("X-Correlation-Id", downloaded))
                .andExpect(request().asyncStarted());
        String denied = UUID.randomUUID().toString();
        mockMvc.perform(csv(otherId, TODAY, TODAY).with(customer).header("X-Correlation-Id", denied))
                .andExpect(status().isForbidden());

        AuditLog view = single(viewed);
        assertThat(view.getAction()).isEqualTo("STATEMENT_VIEW");
        assertThat(view.getTargetType()).isEqualTo("ACCOUNT");
        assertThat(view.getTargetId()).isEqualTo(String.valueOf(accountId));
        assertThat(view.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(jsonb(view)).contains("\"from\": \"" + TODAY.minusDays(3) + "\"")
                .contains("\"to\": \"" + TODAY + "\"");
        AuditLog download = single(downloaded);
        assertThat(download.getAction()).isEqualTo("STATEMENT_DOWNLOAD");
        assertThat(download.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(download.getTargetId()).isEqualTo(String.valueOf(accountId));
        AuditLog refused = single(denied);
        assertThat(refused.getAction()).isEqualTo("STATEMENT_DOWNLOAD");
        assertThat(refused.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(refused.getErrorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(refused.getTargetId()).isEqualTo(String.valueOf(otherId));
    }

    private List<StatementLine> allPages(long accountId, LocalDate from, LocalDate to, int size,
            RequestPostProcessor caller) throws Exception {
        List<StatementLine> all = new ArrayList<>();
        String cursor = null;
        do {
            StatementResponse page = page(accountId, from, to, cursor, size, caller);
            all.addAll(page.lines());
            cursor = page.nextCursor();
            if (cursor != null) {
                assertThat(page.lines()).hasSize(size);
            }
        } while (cursor != null);
        return all;
    }

    private StatementResponse page(long accountId, LocalDate from, LocalDate to, String cursor, int size,
            RequestPostProcessor caller) throws Exception {
        MockHttpServletRequestBuilder request = statement(accountId, from, to).param("size", String.valueOf(size));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (caller != null) {
            request.with(caller);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, StatementResponse.class);
    }

    private static MockHttpServletRequestBuilder statement(long accountId, LocalDate from, LocalDate to) {
        return get(STATEMENT, accountId).param("from", from.toString()).param("to", to.toString());
    }

    private static MockHttpServletRequestBuilder csv(long accountId, LocalDate from, LocalDate to) {
        return get(STATEMENT_CSV, accountId).param("from", from.toString()).param("to", to.toString());
    }

    private void deposit(long accountId, BigDecimal amount, String remarks) {
        tellerService.deposit(accountId, new CashRequest(amount, "INR", remarks));
    }

    private void withdraw(long accountId, BigDecimal amount) {
        withdraw(accountId, amount, null);
    }

    private void withdraw(long accountId, BigDecimal amount, String remarks) {
        tellerService.withdraw(accountId, new CashRequest(amount, "INR", remarks));
    }

    private void transfer(long fromId, long toId, BigDecimal amount) {
        transferService.transfer(UUID.randomUUID().toString(), new TransferRequest(
                testAccounts.accountNumber(fromId), testAccounts.accountNumber(toId), amount, "INR", null));
    }

    private String jsonb(AuditLog row) {
        return jdbc.queryForObject("select details::text from audit_log where id = ?", String.class, row.getId());
    }

    private AuditLog single(String correlationId) {
        List<AuditLog> rows = auditLogRepository.findByCorrelationIdOrderByIdAsc(correlationId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static void expectDenied(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }
}
