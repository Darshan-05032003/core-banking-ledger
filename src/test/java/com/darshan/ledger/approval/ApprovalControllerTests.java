package com.darshan.ledger.approval;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.web.PageResponse;
import com.darshan.ledger.transfer.TransferErrorCode;
import com.darshan.ledger.transfer.TransferResponse;
import com.darshan.ledger.transfer.TransferStatus;
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

@WebMvcTest(ApprovalController.class)
@WithMockUser(roles = "CHECKER")
class ApprovalControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalService approvalService;

    @Test
    void approvingReturnsTheCompletedTransfer() throws Exception {
        given(approvalService.approve(12L)).willReturn(response(TransferStatus.COMPLETED, "checker-1"));

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", 12L).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decidedBy").value("checker-1"))
                .andExpect(jsonPath("$.journalReference").value("JRN-0000000040"))
                .andExpect(content().string(containsString("\"amount\":250000.00")));
    }

    @Test
    void aMakerDecidingTheirOwnTransferIs422() throws Exception {
        given(approvalService.approve(12L)).willThrow(new BusinessRuleException(
                ApprovalErrorCode.SELF_APPROVAL_FORBIDDEN, "Transfer TRF-0000000012 was initiated by you"));

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", 12L).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SELF_APPROVAL_FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aDecisionOnATransferThatIsNoLongerPendingIs409() throws Exception {
        given(approvalService.reject(any(), any())).willThrow(new ConflictException(
                TransferErrorCode.TRANSFER_NOT_PENDING, "Transfer TRF-0000000012 is COMPLETED"));

        mockMvc.perform(post("/api/v1/approvals/{id}/reject", 12L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PENDING"));
    }

    @Test
    void rejectingNeedsAReason() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{id}/reject", 12L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        verifyNoInteractions(approvalService);
    }

    @Test
    void theQueueIsPagedWithDefaultsAndBoundedPageSizes() throws Exception {
        TransferResponse pending = response(TransferStatus.PENDING_APPROVAL, null);
        given(approvalService.list(TransferStatus.PENDING_APPROVAL, 0, 20))
                .willReturn(new PageResponse<>(List.of(pending), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(12))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/approvals").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
        mockMvc.perform(get("/api/v1/approvals").param("status", "SOMETHING"))
                .andExpect(status().isBadRequest());
    }

    private static TransferResponse response(TransferStatus status, String decidedBy) {
        Instant now = Instant.parse("2026-10-22T10:15:30Z");
        boolean completed = status == TransferStatus.COMPLETED;
        return new TransferResponse(12L, "TRF-0000000012", status, 23L, "XXXXXXXX0023", 57L, "XXXXXXXX0057",
                new BigDecimal("250000.00"), "INR", "Car", completed ? 40L : null,
                completed ? "JRN-0000000040" : null, null, null, "maker", now, completed ? now : null, decidedBy, null);
    }
}
