package com.darshan.ledger.interest;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static com.darshan.ledger.FixedClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.MutableClock;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.account.AccountType;
import com.darshan.ledger.account.OpenAccountRequest;
import com.darshan.ledger.audit.AuditLog;
import com.darshan.ledger.audit.AuditLogRepository;
import com.darshan.ledger.audit.AuditOutcome;
import com.darshan.ledger.ledger.CashRequest;
import com.darshan.ledger.ledger.TellerService;
import com.darshan.ledger.security.Role;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// Deposits are posted at the fixed NOW (2026-10-22 10:15 UTC), so a day can only be accrued
// once the clock has been moved past it; every test that moves the clock puts it back.
@IntegrationTest
class InterestAccrualIntegrationTests {

    private static final LocalDate MONTH_END = LocalDate.of(2026, 10, 31);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TellerService tellerService;

    @Autowired
    private InterestAccrualService service;

    @Autowired
    private InterestAccrualJob job;

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
    void aDaysInterestIsTheEndOfDayBalanceTimesTheRateOver365AtFourDecimals() {
        long accountId = testAccounts.openFundedAccount("100000.00");
        clock.advance(Duration.ofDays(1));

        InterestAccrualResult result = service.accrue(TODAY);

        assertThat(result.accrualDate()).isEqualTo(TODAY);
        assertThat(result.accrued()).isGreaterThanOrEqualTo(1);
        assertThat(result.monthEnd()).isFalse();
        assertThat(result.posted()).isZero();
        Map<String, Object> row = accrualRow(accountId, TODAY);
        assertThat((BigDecimal) row.get("balance")).isEqualTo(new BigDecimal("100000.00"));
        assertThat((BigDecimal) row.get("annual_rate")).isEqualByComparingTo("0.0350");
        assertThat((BigDecimal) row.get("amount")).isEqualTo(new BigDecimal("9.5890"));
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(row.get("posted_journal_id")).isNull();
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void aLateRunStillUsesTheBalanceTheAccountHadWhenTheDayEnded() {
        long accountId = testAccounts.openFundedAccount("1000.00");
        clock.advance(Duration.ofDays(1));
        testAccounts.fund(accountId, "500.00");
        clock.advance(Duration.ofDays(1));

        service.accrue(TODAY);
        service.accrue(TODAY.plusDays(1));

        assertThat((BigDecimal) accrualRow(accountId, TODAY).get("balance")).isEqualTo(new BigDecimal("1000.00"));
        assertThat((BigDecimal) accrualRow(accountId, TODAY).get("amount")).isEqualTo(new BigDecimal("0.0959"));
        assertThat((BigDecimal) accrualRow(accountId, TODAY.plusDays(1)).get("balance"))
                .isEqualTo(new BigDecimal("1500.00"));
        assertThat((BigDecimal) accrualRow(accountId, TODAY.plusDays(1)).get("amount"))
                .isEqualTo(new BigDecimal("0.1438"));
    }

    @Test
    void runningTheSameDayTwiceInsertsNothingTheSecondTime() {
        long accountId = testAccounts.openFundedAccount("5000.00");
        clock.advance(Duration.ofDays(1));

        InterestAccrualResult first = service.accrue(TODAY);
        testAccounts.fund(accountId, "5000.00");
        InterestAccrualResult second = service.accrue(TODAY);

        assertThat(first.accrued()).isGreaterThanOrEqualTo(1);
        assertThat(second.accrued()).isZero();
        assertThat(second.alreadyAccrued()).isEqualTo(second.accountsProcessed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from interest_accrual where account_id = ?", Integer.class,
                accountId)).isEqualTo(1);
        assertThat((BigDecimal) accrualRow(accountId, TODAY).get("balance")).isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    void monthEndPostsTheSummedAccrualAsOneInterestJournalAndARerunDoesNotPostAgain() {
        long accountId = testAccounts.openFundedAccount("250000.00");
        clock.advance(Duration.ofDays(10));

        assertThat(service.accrue(MONTH_END.minusDays(2)).monthEnd()).isFalse();
        assertThat(service.accrue(MONTH_END.minusDays(1)).posted()).isZero();
        InterestAccrualResult monthEnd = service.accrue(MONTH_END);

        assertThat(monthEnd.monthEnd()).isTrue();
        assertThat(monthEnd.posted()).isGreaterThanOrEqualTo(1);
        assertThat(monthEnd.postingFailures()).isZero();
        List<Map<String, Object>> interestJournals = interestJournalsOf(accountId);
        assertThat(interestJournals).hasSize(1);
        Map<String, Object> journal = interestJournals.getFirst();
        long journalId = ((Number) journal.get("id")).longValue();
        assertThat(journal.get("type")).isEqualTo("INTEREST");
        assertThat(journal.get("description")).isEqualTo("Savings interest accrued to 2026-10-31");
        assertThat((BigDecimal) journal.get("amount")).isEqualTo(new BigDecimal("71.92"));
        assertThat(jdbc.queryForObject("""
                select count(*) from ledger_entry le join gl_account g on g.id = le.gl_account_id
                where le.journal_entry_id = ? and g.code = 'INTEREST_EXPENSE' and le.direction = 'DEBIT'
                  and le.amount = 71.92
                """, Integer.class, journalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ledger_balance from account_balance where account_id = ?",
                BigDecimal.class, accountId)).isEqualTo(new BigDecimal("250071.92"));
        assertThat(jdbc.queryForObject("select available_balance from account_balance where account_id = ?",
                BigDecimal.class, accountId)).isEqualTo(new BigDecimal("250071.92"));
        assertThat(jdbc.queryForList("select posted_journal_id from interest_accrual where account_id = ? order by"
                + " accrual_date", Long.class, accountId)).containsExactly(journalId, journalId, journalId);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Integer.class,
                String.valueOf(journalId))).isEqualTo(1);

        InterestAccrualResult rerun = service.accrue(MONTH_END);

        assertThat(rerun.accrued()).isZero();
        assertThat(rerun.posted()).isZero();
        assertThat(interestJournalsOf(accountId)).hasSize(1);
        assertThat(jdbc.queryForObject("select ledger_balance from account_balance where account_id = ?",
                BigDecimal.class, accountId)).isEqualTo(new BigDecimal("250071.92"));
    }

    @Test
    void interestBelowOnePaisaIsCarriedForwardInsteadOfPosted() {
        long accountId = testAccounts.openFundedAccount("1.00");
        clock.advance(Duration.ofDays(10));

        service.accrue(MONTH_END.minusDays(2));
        service.accrue(MONTH_END.minusDays(1));
        InterestAccrualResult monthEnd = service.accrue(MONTH_END);

        assertThat(monthEnd.carriedForward()).isGreaterThanOrEqualTo(1);
        assertThat(interestJournalsOf(accountId)).isEmpty();
        assertThat(jdbc.queryForList("select amount from interest_accrual where account_id = ? and"
                + " posted_journal_id is null", BigDecimal.class, accountId))
                .containsExactly(new BigDecimal("0.0001"), new BigDecimal("0.0001"), new BigDecimal("0.0001"));
        assertThat(jdbc.queryForObject("select ledger_balance from account_balance where account_id = ?",
                BigDecimal.class, accountId)).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void currentAndClosedAccountsEarnNothingWhileAFrozenSavingsAccountStillAccrues() {
        long savingsId = testAccounts.openFundedAccount("10000.00");
        long customerId = testAccounts.customerIdOf(savingsId);
        long currentId = accountService.open(new OpenAccountRequest(customerId, AccountType.CURRENT, "INR")).id();
        tellerService.deposit(currentId, new CashRequest(new BigDecimal("10000.00"), "INR", null));
        long closedId = testAccounts.openActiveAccount();
        accountService.close(closedId);
        long frozenId = testAccounts.openFundedAccount("10000.00");
        accountService.freeze(frozenId);
        clock.advance(Duration.ofDays(1));

        service.accrue(TODAY);

        assertThat(accrualRows(currentId)).isEmpty();
        assertThat(accrualRows(closedId)).isEmpty();
        assertThat((BigDecimal) accrualRow(savingsId, TODAY).get("amount")).isEqualTo(new BigDecimal("0.9589"));
        assertThat((BigDecimal) accrualRow(frozenId, TODAY).get("amount")).isEqualTo(new BigDecimal("0.9589"));
    }

    @Test
    void aDayThatHasNotEndedCannotBeAccrued() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").param("date", TODAY.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ACCRUAL_DATE_NOT_ENDED"));
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").param("date", TODAY.plusDays(1).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ACCRUAL_DATE_NOT_ENDED"));
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").param("date", "31/10/2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAdministratorCanRunADayAndTheRunIsAuditedUnderTheirName() throws Exception {
        long accountId = testAccounts.openFundedAccount("36500.00");
        TestUser admin = testUsers.create(Role.ADMIN);
        String correlationId = UUID.randomUUID().toString();
        clock.advance(Duration.ofDays(1));

        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual")
                        .param("date", TODAY.toString())
                        .with(testUsers.bearer(admin))
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accrualDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.accountsProcessed").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.monthEnd").value(false))
                .andExpect(jsonPath("$.posted").value(0));

        assertThat((BigDecimal) accrualRow(accountId, TODAY).get("amount")).isEqualTo(new BigDecimal("3.5000"));
        List<AuditLog> rows = auditLogRepository.findByCorrelationIdOrderByIdAsc(correlationId);
        assertThat(rows).hasSize(1);
        AuditLog row = rows.getFirst();
        assertThat(row.getAction()).isEqualTo("INTEREST_ACCRUAL_RUN");
        assertThat(row.getActor()).isEqualTo(admin.username());
        assertThat(row.getRoles()).isEqualTo("ADMIN");
        assertThat(row.getTargetType()).isEqualTo("ACCRUAL_DATE");
        assertThat(row.getTargetId()).isEqualTo(TODAY.toString());
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(jsonb(row)).contains("\"accrued\": ").contains("\"posted\": 0");
    }

    @Test
    void theScheduledRunAccruesYesterdayUnderTheShedLockAndIsAuditedAsSystem() {
        long accountId = testAccounts.openFundedAccount("100000.00");
        clock.advance(Duration.ofDays(1));

        job.run();

        assertThat((BigDecimal) accrualRow(accountId, TODAY).get("amount")).isEqualTo(new BigDecimal("9.5890"));
        String lockedBy = jdbc.queryForObject("select locked_by from shedlock where name = ?", String.class,
                InterestAccrualJob.LOCK_NAME);
        OffsetDateTime lockUntil = jdbc.queryForObject("select lock_until from shedlock where name = ?",
                OffsetDateTime.class, InterestAccrualJob.LOCK_NAME);
        assertThat(lockedBy).isNotBlank();
        assertThat(lockUntil).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
        AuditLog row = auditLogRepository
                .findByTargetTypeAndTargetIdOrderByIdAsc("ACCRUAL_DATE", TODAY.toString()).stream()
                .filter(candidate -> candidate.getAction().equals("INTEREST_ACCRUAL"))
                .toList().getLast();
        assertThat(row.getActor()).isEqualTo("system");
        assertThat(row.getRoles()).isEmpty();
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getClientIp()).isNull();
        assertThat(jsonb(row)).contains("\"monthEnd\": false");
    }

    private Map<String, Object> accrualRow(long accountId, LocalDate date) {
        return jdbc.queryForMap("select * from interest_accrual where account_id = ? and accrual_date = ?", accountId,
                date);
    }

    private List<Map<String, Object>> accrualRows(long accountId) {
        return jdbc.queryForList("select * from interest_accrual where account_id = ?", accountId);
    }

    private List<Map<String, Object>> interestJournalsOf(long accountId) {
        return jdbc.queryForList("""
                select je.id, je.type, je.description, le.amount
                from journal_entry je join ledger_entry le on le.journal_entry_id = je.id
                where je.type = 'INTEREST' and le.account_id = ? and le.direction = 'CREDIT'
                order by je.id
                """, accountId);
    }

    private String jsonb(AuditLog row) {
        return jdbc.queryForObject("select details::text from audit_log where id = ?", String.class, row.getId());
    }
}
