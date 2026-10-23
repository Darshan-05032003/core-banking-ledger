package com.darshan.ledger.customer;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

// Each state change is its own endpoint so that it can carry its own role rule.
@RestController
@Tag(name = ApiTags.CUSTOMERS)
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(operationId = "onboardCustomer", summary = "Onboard a customer; KYC starts as PENDING")
    @ApiResponse(responseCode = "201", description = "Customer created")
    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    public ResponseEntity<CustomerResponse> onboard(@Valid @RequestBody OnboardCustomerRequest request) {
        CustomerResponse customer = customerService.onboard(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(customer.id())
                .toUri();
        return ResponseEntity.created(location).body(customer);
    }

    @Operation(operationId = "getCustomer", summary = "Fetch a customer")
    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @customerAccess.isSelf(#customerId, authentication))")
    public CustomerResponse get(@PathVariable Long customerId) {
        return customerService.get(customerId);
    }

    @Operation(operationId = "verifyKyc", summary = "Mark the customer's KYC as VERIFIED")
    @PostMapping("/{customerId}/kyc/verify")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Audited(action = "KYC_VERIFY", targetType = "CUSTOMER", targetId = "#customerId")
    public CustomerResponse verifyKyc(@PathVariable Long customerId) {
        return customerService.verifyKyc(customerId);
    }

    @Operation(operationId = "rejectKyc", summary = "Mark the customer's KYC as REJECTED with a reason")
    @PostMapping("/{customerId}/kyc/reject")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Audited(action = "KYC_REJECT", targetType = "CUSTOMER", targetId = "#customerId",
            details = "{reason: #request.reason()}")
    public CustomerResponse rejectKyc(@PathVariable Long customerId, @Valid @RequestBody RejectKycRequest request) {
        return customerService.rejectKyc(customerId, request);
    }
}
