package com.darshan.ledger.auth;

import static com.darshan.ledger.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.darshan.ledger.security.JwtClaims;
import com.darshan.ledger.security.Role;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthenticationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestUsers testUsers;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void validCredentialsProduceASignedTokenCarryingRolesAndTheLinkedCustomer() throws Exception {
        long customerId = testAccounts.customerIdOf(testAccounts.openActiveAccount());
        TestUser customer = testUsers.createCustomer(customerId);

        String body = token(customer.username(), TestUsers.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plus(Duration.ofMinutes(15)).toString()))
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
        assertThat(jwt.getSubject()).isEqualTo(customer.username());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("core-banking-ledger");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).isEqualTo(List.of("CUSTOMER"));
        assertThat(jwt.<Number>getClaim(JwtClaims.CUSTOMER_ID).longValue()).isEqualTo(customerId);
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void staffTokensCarryEveryRoleAndNoCustomerClaim() throws Exception {
        TestUser manager = testUsers.create(Role.TELLER, Role.CHECKER);

        Jwt jwt = jwtDecoder.decode(testUsers.tokenFor(manager));

        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactly("CHECKER", "TELLER");
        assertThat(jwt.hasClaim(JwtClaims.CUSTOMER_ID)).isFalse();
    }

    @Test
    void usernamesAreCaseInsensitiveAtLogin() throws Exception {
        TestUser teller = testUsers.create(Role.TELLER);

        token("  " + teller.username().toUpperCase() + " ", TestUsers.PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordAndAnUnknownUserAreRefusedIdentically() throws Exception {
        TestUser teller = testUsers.create(Role.TELLER);

        token(teller.username(), "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.correlationId").exists());
        token("nobody-here", TestUsers.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void aDisabledUserCannotLogInUntilReEnabled() throws Exception {
        TestUser teller = testUsers.create(Role.TELLER);
        mockMvc.perform(post("/api/v1/admin/users/{id}/disable", teller.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        token(teller.username(), TestUsers.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("USER_DISABLED"));
        mockMvc.perform(post("/api/v1/admin/users/{id}/disable", teller.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_STATUS_UNCHANGED"));

        mockMvc.perform(post("/api/v1/admin/users/{id}/enable", teller.id())).andExpect(status().isOk());
        token(teller.username(), TestUsers.PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theTokenRequestIsValidated() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token").with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void aRequestWithoutATokenIs401WithAProblemBody() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1").with(anonymous()).header("X-Correlation-Id", "anon-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/accounts/1"))
                .andExpect(jsonPath("$.correlationId").value("anon-1"));
    }

    @Test
    void anExpiredTokenIsRefusedWithItsOwnErrorCode() throws Exception {
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer("core-banking-ledger")
                        .subject("ghost")
                        .issuedAt(NOW.minus(Duration.ofMinutes(20)))
                        .expiresAt(NOW.minus(Duration.ofMinutes(5)))
                        .claim(JwtClaims.ROLES, List.of("ADMIN"))
                        .build())).getTokenValue();

        mockMvc.perform(get("/api/v1/accounts/1").with(TestUsers.bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("The access token has expired"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aTamperedTokenIsRefused() throws Exception {
        String token = testUsers.tokenFor(testUsers.create(Role.ADMIN));
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        mockMvc.perform(get("/api/v1/accounts/1").with(TestUsers.bearer(tampered)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.detail").value("The access token is invalid"));
        mockMvc.perform(get("/api/v1/accounts/1").with(TestUsers.bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenFromAnotherIssuerIsRefusedEvenWithTheRightSignature() throws Exception {
        String foreign = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer("someone-else")
                        .subject("ghost")
                        .issuedAt(NOW)
                        .expiresAt(NOW.plus(Duration.ofMinutes(15)))
                        .claim(JwtClaims.ROLES, List.of("ADMIN"))
                        .build())).getTokenValue();

        mockMvc.perform(get("/api/v1/accounts/1").with(TestUsers.bearer(foreign)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    private ResultActions token(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/token").with(anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }
}
