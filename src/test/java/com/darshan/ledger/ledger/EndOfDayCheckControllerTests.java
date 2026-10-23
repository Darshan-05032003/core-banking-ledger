package com.darshan.ledger.ledger;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EndOfDayCheckController.class)
@WithMockUser(roles = "ADMIN")
class EndOfDayCheckControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndOfDayCheck check;

    @Test
    void aDatedRunReturnsTheReportWithMaskedAccountNumbers() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 21);
        given(check.run(date)).willReturn(new EndOfDayCheckResult(date, 1200, 1,
                List.of(new BalanceMismatch(42, "XXXXXXXX4821", "INR", new BigDecimal("1000.00"),
                        new BigDecimal("1100.00"), new BigDecimal("1100.00"), new BigDecimal("1000.00"))),
                50000, 0, List.of(),
                List.of(new CurrencyTotals("INR", new BigDecimal("9000.00"), new BigDecimal("9000.00"))), false));

        mockMvc.perform(post("/api/v1/admin/jobs/eod-check").with(csrf()).param("date", "2026-10-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-10-21"))
                .andExpect(jsonPath("$.accountsChecked").value(1200))
                .andExpect(jsonPath("$.balanceMismatches").value(1))
                .andExpect(jsonPath("$.mismatchedBalances[0].accountId").value(42))
                .andExpect(jsonPath("$.mismatchedBalances[0].maskedAccountNumber").value("XXXXXXXX4821"))
                .andExpect(jsonPath("$.mismatchedBalances[0].expectedLedgerBalance").value(1000.00))
                .andExpect(jsonPath("$.mismatchedBalances[0].actualLedgerBalance").value(1100.00))
                .andExpect(jsonPath("$.journalsChecked").value(50000))
                .andExpect(jsonPath("$.unbalancedJournals").value(0))
                .andExpect(jsonPath("$.totals[0].currency").value("INR"))
                .andExpect(jsonPath("$.totals[0].debits").value(9000.00))
                .andExpect(jsonPath("$.passed").value(false));
    }

    @Test
    void withoutADateTheCheckRunsForToday() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 22);
        given(check.run()).willReturn(new EndOfDayCheckResult(today, 3, 0, List.of(), 7, 0, List.of(), List.of(),
                true));

        mockMvc.perform(post("/api/v1/admin/jobs/eod-check").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-10-22"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void aMalformedDateIsRejectedBeforeTheCheckRuns() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/eod-check").with(csrf()).param("date", "yesterday"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(check);
    }

    @Test
    void aDateTheCheckRefusesIsA422WithItsCode() throws Exception {
        LocalDate future = LocalDate.of(2027, 1, 1);
        given(check.run(future)).willThrow(
                new BusinessRuleException(LedgerErrorCode.BUSINESS_DATE_IN_FUTURE, "Not yet"));

        mockMvc.perform(post("/api/v1/admin/jobs/eod-check").with(csrf()).param("date", "2027-01-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_DATE_IN_FUTURE"))
                .andExpect(jsonPath("$.detail").value("Not yet"));
    }
}
