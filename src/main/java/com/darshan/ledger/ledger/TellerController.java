package com.darshan.ledger.ledger;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<CashOperationResponse> deposit(@PathVariable Long accountId,
            @Valid @RequestBody CashRequest request) {
        return created(tellerService.deposit(accountId, request));
    }

    @PostMapping("/api/v1/accounts/{accountId}/withdrawals")
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
