package com.darshan.ledger.transfer;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.idempotency.IdempotencyService;
import com.darshan.ledger.common.idempotency.StoredResponse;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = ApiTags.TRANSFERS)
public class TransferController {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;
    private final TransferRateLimiter rateLimiter;

    public TransferController(TransferService transferService, TransferRateLimiter rateLimiter) {
        this.transferService = transferService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(operationId = "createTransfer", summary = "Transfer money between two customer accounts",
            description = "Validated by the rule chain under row locks and posted as a journal. Above the "
                    + "approval threshold the transfer is parked with a hold until a checker decides.")
    @ApiResponse(responseCode = "201", description = "Transfer posted",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "202", description = "Transfer waiting for a checker; a hold was placed",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @PostMapping("/api/v1/transfers")
    @PreAuthorize("hasRole('ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.ownsAccountNumber(#request.fromAccount(), authentication))")
    @Audited(action = "TRANSFER", targetType = "TRANSFER",
            targetId = "#result instanceof T(com.darshan.ledger.transfer.TransferResponse) ? #result.id() : null",
            details = "{fromAccount: #request.maskedFromAccount(), toAccount: #request.maskedToAccount(), "
                    + "amount: #request.amount(), currency: #request.currency()}")
    public ResponseEntity<Object> transfer(
            @Parameter(in = ParameterIn.HEADER, name = IdempotencyService.HEADER, required = true,
                    description = "Unique per request from this caller; a retry with the same key gets the first "
                            + "response back", example = "3f7a1c2e-9b4d-4e8a-8c1f-5d6e7f8a9b0c")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request, Authentication authentication) {
        rateLimiter.acquire(authentication.getName());
        return switch (transferService.transfer(idempotencyKey, request)) {
            case TransferOutcome.Created created -> ResponseEntity.created(location(created.response().id()))
                    .body(created.response());
            case TransferOutcome.PendingApproval pending -> ResponseEntity.accepted()
                    .location(location(pending.response().id()))
                    .body(pending.response());
            case TransferOutcome.Replayed replayed -> replay(replayed.response());
        };
    }

    @Operation(operationId = "getTransfer", summary = "A transfer attempt in any state")
    @GetMapping("/api/v1/transfers/{transferId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @transferAccess.isSender(#transferId, authentication))")
    public TransferResponse get(@PathVariable Long transferId) {
        return transferService.get(transferId);
    }

    // The stored body goes back exactly as it was produced the first time, whichever status it had.
    private static ResponseEntity<Object> replay(StoredResponse stored) {
        return ResponseEntity.status(stored.status())
                .header(REPLAYED_HEADER, "true")
                .contentType(stored.isError() ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)
                .body(stored.body());
    }

    private static URI location(Long transferId) {
        return ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(transferId).toUri();
    }
}
