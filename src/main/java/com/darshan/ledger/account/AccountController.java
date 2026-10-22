package com.darshan.ledger.account;

import com.darshan.ledger.audit.Audited;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/v1/accounts")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        AccountResponse account = accountService.open(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(account.id())
                .toUri();
        return ResponseEntity.created(location).body(account);
    }

    @GetMapping("/api/v1/accounts/{accountId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    public AccountResponse get(@PathVariable Long accountId) {
        return accountService.get(accountId);
    }

    @GetMapping("/api/v1/accounts/{accountId}/balance")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    public AccountBalanceResponse balance(@PathVariable Long accountId) {
        return accountService.balance(accountId);
    }

    @GetMapping("/api/v1/customers/{customerId}/accounts")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @customerAccess.isSelf(#customerId, authentication))")
    public List<AccountResponse> listForCustomer(@PathVariable Long customerId) {
        return accountService.listForCustomer(customerId);
    }

    @PostMapping("/api/v1/accounts/{accountId}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_FREEZE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse freeze(@PathVariable Long accountId) {
        return accountService.freeze(accountId);
    }

    @PostMapping("/api/v1/accounts/{accountId}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_UNFREEZE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse unfreeze(@PathVariable Long accountId) {
        return accountService.unfreeze(accountId);
    }

    @PostMapping("/api/v1/accounts/{accountId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_CLOSE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse close(@PathVariable Long accountId) {
        return accountService.close(accountId);
    }
}
