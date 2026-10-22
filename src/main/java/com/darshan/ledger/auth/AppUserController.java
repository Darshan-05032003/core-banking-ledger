package com.darshan.ledger.auth;

import com.darshan.ledger.audit.Audited;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AppUserController {

    private final AppUserService appUserService;

    public AppUserController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_CREATE", targetType = "APP_USER", targetId = "#result?.id()",
            details = "{username: #request.username(), roles: #request.roles(), customerId: #request.customerId()}")
    public ResponseEntity<AppUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        AppUserResponse user = appUserService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(user.id())
                .toUri();
        return ResponseEntity.created(location).body(user);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AppUserResponse get(@PathVariable Long userId) {
        return appUserService.get(userId);
    }

    @PutMapping("/{userId}/customer")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_LINK_CUSTOMER", targetType = "APP_USER", targetId = "#userId",
            details = "{customerId: #request.customerId()}")
    public AppUserResponse linkCustomer(@PathVariable Long userId, @Valid @RequestBody LinkCustomerRequest request) {
        return appUserService.linkCustomer(userId, request);
    }

    @PostMapping("/{userId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_DISABLE", targetType = "APP_USER", targetId = "#userId")
    public AppUserResponse disable(@PathVariable Long userId) {
        return appUserService.disable(userId);
    }

    @PostMapping("/{userId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_ENABLE", targetType = "APP_USER", targetId = "#userId")
    public AppUserResponse enable(@PathVariable Long userId) {
        return appUserService.enable(userId);
    }
}
