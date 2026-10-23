package com.darshan.ledger.approval;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import com.darshan.ledger.common.web.PageResponse;
import com.darshan.ledger.transfer.TransferResponse;
import com.darshan.ledger.transfer.TransferStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// ADMIN may decide as well as CHECKER: in this system ADMIN is the superset of every staff role.
// The four-eyes rule in the service still applies to it.
@RestController
@Tag(name = ApiTags.APPROVALS)
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Operation(operationId = "listApprovals", summary = "The approval queue, oldest first")
    @GetMapping("/api/v1/approvals")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public PageResponse<TransferResponse> list(
            @RequestParam(defaultValue = "PENDING_APPROVAL") TransferStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return approvalService.list(status, page, size);
    }

    @Operation(operationId = "approveTransfer", summary = "Post a pending transfer; the maker may not decide")
    @PostMapping("/api/v1/approvals/{transferId}/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    @Audited(action = "TRANSFER_APPROVE", targetType = "TRANSFER", targetId = "#transferId")
    public TransferResponse approve(@PathVariable Long transferId) {
        return approvalService.approve(transferId);
    }

    @Operation(operationId = "rejectTransfer", summary = "Refuse a pending transfer and release its hold")
    @PostMapping("/api/v1/approvals/{transferId}/reject")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    @Audited(action = "TRANSFER_REJECT", targetType = "TRANSFER", targetId = "#transferId",
            details = "{reason: #request.reason()}")
    public TransferResponse reject(@PathVariable Long transferId, @Valid @RequestBody RejectTransferRequest request) {
        return approvalService.reject(transferId, request);
    }
}
