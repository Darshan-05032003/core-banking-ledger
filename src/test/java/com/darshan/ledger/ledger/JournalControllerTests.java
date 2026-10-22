package com.darshan.ledger.ledger;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
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

@WebMvcTest(JournalController.class)
@WithMockUser
class JournalControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JournalService journalService;

    @Test
    void returnsAJournalWithItsLegs() throws Exception {
        given(journalService.get(40L)).willReturn(journal(40L, JournalType.DEPOSIT, null));

        mockMvc.perform(get("/api/v1/journals/40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("JRN-0000000040"))
                .andExpect(jsonPath("$.postedAt").value("2026-10-22T10:15:30Z"))
                .andExpect(jsonPath("$.reversalOfId").doesNotExist())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[1].maskedAccountNumber").value("XXXXXXXX0014"));
    }

    @Test
    void unknownJournalIs404() throws Exception {
        given(journalService.get(99L)).willThrow(new ResourceNotFoundException("Journal", 99L));

        mockMvc.perform(get("/api/v1/journals/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void reversalReturns201PointingAtTheNewJournal() throws Exception {
        given(journalService.reverse(40L, new ReversalRequest("Wrong account")))
                .willReturn(journal(41L, JournalType.REVERSAL, 40L));

        mockMvc.perform(post("/api/v1/journals/40/reversal").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong account\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/journals/41")))
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOfId").value(40));
    }

    @Test
    void reversalNeedsAReason() throws Exception {
        mockMvc.perform(post("/api/v1/journals/40/reversal").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'reason')]").exists());

        verifyNoInteractions(journalService);
    }

    @Test
    void secondReversalIsAConflict() throws Exception {
        given(journalService.reverse(eq(40L), any())).willThrow(new ConflictException(
                LedgerErrorCode.JOURNAL_ALREADY_REVERSED, "Journal JRN-0000000040 has already been reversed"));

        mockMvc.perform(post("/api/v1/journals/40/reversal").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("JOURNAL_ALREADY_REVERSED"));
    }

    private static JournalResponse journal(long id, JournalType type, Long reversalOfId) {
        return new JournalResponse(id, "JRN-%010d".formatted(id), type, "Cash", Instant.parse("2026-10-22T10:15:30Z"),
                "teller1", reversalOfId, List.of(
                new LedgerEntryResponse(null, null, GlAccountCode.CASH_VAULT, EntryDirection.DEBIT, new BigDecimal("500.00"), "INR"),
                new LedgerEntryResponse(10L, "XXXXXXXX0014", null, EntryDirection.CREDIT, new BigDecimal("500.00"), "INR")));
    }
}
