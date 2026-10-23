package com.darshan.ledger.account;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = ApiTags.ACCOUNTS)
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(operationId = "openAccount", summary = "Open an account for a KYC-verified customer")
    @ApiResponse(responseCode = "201", description = "Account opened")
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

    @Operation(operationId = "getAccount", summary = "Fetch an account")
    @GetMapping("/api/v1/accounts/{accountId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    public AccountResponse get(@PathVariable Long accountId) {
        return accountService.get(accountId);
    }

    @Operation(operationId = "getBalance", summary = "Ledger balance, available balance and the amount on hold")
    @GetMapping("/api/v1/accounts/{accountId}/balance")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    public AccountBalanceResponse balance(@PathVariable Long accountId) {
        return accountService.balance(accountId);
    }

    @Operation(operationId = "listCustomerAccounts", summary = "List a customer's accounts")
    @GetMapping("/api/v1/customers/{customerId}/accounts")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @customerAccess.isSelf(#customerId, authentication))")
    public List<AccountResponse> listForCustomer(@PathVariable Long customerId) {
        return accountService.listForCustomer(customerId);
    }

    @Operation(operationId = "freezeAccount", summary = "Freeze an account so it can no longer transact")
    @PostMapping("/api/v1/accounts/{accountId}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_FREEZE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse freeze(@PathVariable Long accountId) {
        return accountService.freeze(accountId);
    }

    @Operation(operationId = "unfreezeAccount", summary = "Restore a frozen account to ACTIVE")
    @PostMapping("/api/v1/accounts/{accountId}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_UNFREEZE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse unfreeze(@PathVariable Long accountId) {
        return accountService.unfreeze(accountId);
    }

    @Operation(operationId = "closeAccount", summary = "Close an account whose balance is zero and holds none")
    @PostMapping("/api/v1/accounts/{accountId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACCOUNT_CLOSE", targetType = "ACCOUNT", targetId = "#accountId")
    public AccountResponse close(@PathVariable Long accountId) {
        return accountService.close(accountId);
    }
}
