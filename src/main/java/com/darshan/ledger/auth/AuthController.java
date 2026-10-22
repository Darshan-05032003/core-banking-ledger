package com.darshan.ledger.auth;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/api/v1/auth/token")
    @PreAuthorize("permitAll()")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return tokenService.issue(request);
    }
}
