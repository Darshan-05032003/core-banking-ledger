package com.darshan.ledger.auth;

import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiTags.AUTH)
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Operation(operationId = "issueToken", summary = "Exchange a username and password for an access token")
    @SecurityRequirements
    @PostMapping("/api/v1/auth/token")
    @PreAuthorize("permitAll()")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return tokenService.issue(request);
    }
}
