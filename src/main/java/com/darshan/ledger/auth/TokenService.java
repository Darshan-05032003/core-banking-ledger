package com.darshan.ledger.auth;

import com.darshan.ledger.security.JwtClaims;
import com.darshan.ledger.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

// Password in, signed access token out. The token is self-contained: subject, roles and the
// linked customer id, so no request after this one needs the user table.
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties.Jwt jwt;
    private final Clock clock;

    public TokenService(AuthenticationManager authenticationManager, JwtEncoder jwtEncoder,
            SecurityProperties properties, Clock clock) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwt = properties.jwt();
        this.clock = clock;
    }

    public TokenResponse issue(TokenRequest request) {
        AppUserPrincipal principal = (AppUserPrincipal) authenticate(request).getPrincipal();
        Instant issuedAt = clock.instant();
        Duration ttl = jwt.accessTokenTtl();
        Instant expiresAt = issuedAt.plus(ttl);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(principal.getUsername())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(JwtClaims.ROLES, principal.roleNames());
        principal.linkedCustomerId().ifPresent(customerId -> claims.claim(JwtClaims.CUSTOMER_ID, customerId));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        log.info("Access token issued to user {} with roles {}", principal.getUsername(), principal.roleNames());
        return new TokenResponse(token, TokenResponse.BEARER, ttl.toSeconds(), expiresAt);
    }

    // Unknown user and wrong password are reported identically so the endpoint cannot be used to
    // discover which usernames exist.
    private Authentication authenticate(TokenRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
        } catch (DisabledException | LockedException ex) {
            log.info("Token refused for disabled user {}", username);
            throw new AuthenticationFailedException(AuthErrorCode.USER_DISABLED, "The user account is disabled");
        } catch (AuthenticationException ex) {
            log.info("Token refused for user {}: bad credentials", username);
            throw new AuthenticationFailedException(AuthErrorCode.INVALID_CREDENTIALS, "Invalid username or password");
        }
    }
}
