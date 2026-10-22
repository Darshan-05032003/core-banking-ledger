package com.darshan.ledger.ledger;

import com.darshan.ledger.audit.Audited;
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
public class TellerController {

    private final TellerService tellerService;

    public TellerController(TellerService tellerService) {
        this.tellerService = tellerService;
    }

    @PostMapping("/api/v1/accounts/{accountId}/deposits")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Audited(action = "DEPOSIT", targetType = "ACCOUNT", targetId = "#accountId",
            details = "{amount: #request.amount(), currency: #request.currency()}")
    public ResponseEntity<CashOperationResponse> deposit(@PathVariable Long accountId,
            @Valid @RequestBody CashRequest request) {
        return created(tellerService.deposit(accountId, request));
    }

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
