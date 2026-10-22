package com.darshan.ledger.customer;

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
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

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

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @customerAccess.isSelf(#customerId, authentication))")
    public CustomerResponse get(@PathVariable Long customerId) {
        return customerService.get(customerId);
    }

    @PostMapping("/{customerId}/kyc/verify")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    public CustomerResponse verifyKyc(@PathVariable Long customerId) {
        return customerService.verifyKyc(customerId);
    }

    @PostMapping("/{customerId}/kyc/reject")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    public CustomerResponse rejectKyc(@PathVariable Long customerId, @Valid @RequestBody RejectKycRequest request) {
        return customerService.rejectKyc(customerId, request);
    }
}
