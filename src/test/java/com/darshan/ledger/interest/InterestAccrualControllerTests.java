package com.darshan.ledger.interest;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InterestAccrualController.class)
@WithMockUser(roles = "ADMIN")
class InterestAccrualControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InterestAccrualService service;

    @Test
    void runningADayReturnsWhatTheRunDid() throws Exception {
        LocalDate monthEnd = LocalDate.of(2026, 10, 31);
        given(service.accrue(monthEnd)).willReturn(new InterestAccrualResult(monthEnd, 120, 118, 2, true, 97, 21, 0));

        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").with(csrf()).param("date", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accrualDate").value("2026-10-31"))
                .andExpect(jsonPath("$.accountsProcessed").value(120))
                .andExpect(jsonPath("$.accrued").value(118))
                .andExpect(jsonPath("$.alreadyAccrued").value(2))
                .andExpect(jsonPath("$.monthEnd").value(true))
                .andExpect(jsonPath("$.posted").value(97))
                .andExpect(jsonPath("$.carriedForward").value(21))
                .andExpect(jsonPath("$.postingFailures").value(0));
    }

    @Test
    void aDayTheServiceRefusesIsA422WithItsCode() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 22);
        given(service.accrue(today)).willThrow(
                new BusinessRuleException(InterestErrorCode.ACCRUAL_DATE_NOT_ENDED, "Day not over"));

        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").with(csrf()).param("date", "2026-10-22"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ACCRUAL_DATE_NOT_ENDED"))
                .andExpect(jsonPath("$.detail").value("Day not over"));
    }

    @Test
    void aMissingOrMalformedDateIsRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/jobs/interest-accrual").with(csrf()).param("date", "31-10-2026"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
