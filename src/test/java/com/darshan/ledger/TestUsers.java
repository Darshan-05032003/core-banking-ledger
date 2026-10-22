package com.darshan.ledger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.auth.AppUserService;
import com.darshan.ledger.auth.CreateUserRequest;
import com.darshan.ledger.security.Role;
import com.jayway.jsonpath.JsonPath;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Creates logins with known passwords and turns them into bearer tokens through the real token
// endpoint, so tests about roles and ownership run exactly as a client would.
@TestComponent
public class TestUsers {

    public static final String PASSWORD = "correct-horse-battery-staple";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AppUserService appUserService;
    private final MockMvc mockMvc;

    public TestUsers(AppUserService appUserService, MockMvc mockMvc) {
        this.appUserService = appUserService;
        this.mockMvc = mockMvc;
    }

    public record TestUser(long id, String username, Long customerId) {
    }

    public TestUser create(Role role, Role... more) {
        return create(null, Set.of(combine(role, more)));
    }

    public TestUser createCustomer(long customerId) {
        return create(customerId, Set.of(Role.CUSTOMER));
    }

    public TestUser createUnlinkedCustomer() {
        return create(null, Set.of(Role.CUSTOMER));
    }

    public String tokenFor(TestUser user) {
        return tokenFor(user.username(), PASSWORD);
    }

    public String tokenFor(String username, String password) {
        try {
            String body = mockMvc.perform(post("/api/v1/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.accessToken");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not obtain a token for " + username, ex);
        }
    }

    public RequestPostProcessor bearer(TestUser user) {
        return bearer(tokenFor(user));
    }

    // The shared context runs every request as the mock ADMIN. Spring Security 7 merges a new
    // authentication with the one already present (so a second factor can add authorities), which
    // would give the token holder the mock user's role as well; an empty context per request
    // makes the token the only identity.
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            securityContext(SecurityContextHolder.createEmptyContext()).postProcessRequest(request);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private TestUser create(Long customerId, Set<Role> roles) {
        String username = roles.iterator().next().name().toLowerCase(Locale.ROOT) + SEQUENCE.incrementAndGet();
        long id = appUserService.create(new CreateUserRequest(username, PASSWORD, roles, customerId)).id();
        return new TestUser(id, username, customerId);
    }

    private static Role[] combine(Role role, Role... more) {
        Role[] all = new Role[more.length + 1];
        all[0] = role;
        System.arraycopy(more, 0, all, 1, more.length);
        return all;
    }
}
