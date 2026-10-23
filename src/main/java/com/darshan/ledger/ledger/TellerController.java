package com.darshan.ledger.ledger;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = ApiTags.TELLER)
public class TellerController {

    private final TellerService tellerService;

    public TellerController(TellerService tellerService) {
        this.tellerService = tellerService;
    }

    @Operation(operationId = "deposit", summary = "Deposit cash: debit the vault, credit the account")
    @ApiResponse(responseCode = "201", description = "Journal posted")
    @PostMapping("/api/v1/accounts/{accountId}/deposits")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Audited(action = "DEPOSIT", targetType = "ACCOUNT", targetId = "#accountId",
            details = "{amount: #request.amount(), currency: #request.currency()}")
    public ResponseEntity<CashOperationResponse> deposit(@PathVariable Long accountId,
            @Valid @RequestBody CashRequest request) {
        return created(tellerService.deposit(accountId, request));
    }

    @Operation(operationId = "withdraw", summary = "Withdraw cash: debit the account, credit the vault")
    @ApiResponse(responseCode = "201", description = "Journal posted")
    @PostMapping("/api/v1/accounts/{accountId}/withdrawals")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Audited(action = "WITHDRAWAL", targetType = "ACCOUNT", targetId = "#accountId",
            details = "{amount: #request.amount(), currency: #request.currency()}")
    public ResponseEntity<CashOperationResponse> withdraw(@PathVariable Long accountId,
            @Valid @RequestBody CashRequest request) {
        return created(tellerService.withdraw(accountId, request));
    }

    private static ResponseEntity<CashOperationResponse> created(CashOperationResponse response) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/journals/{id}")
                .buildAndExpand(response.journal().id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }
}
