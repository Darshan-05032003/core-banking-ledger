package com.darshan.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Every endpoint against every kind of caller. Targets that do not exist are deliberate: the
// question here is only whether the caller gets past authentication and authorisation, so any
// status other than 401 and 403 counts as "allowed".
@IntegrationTest
class SecurityMatrixTests {

    enum Caller { ANONYMOUS, CUSTOMER, TELLER, CHECKER, ADMIN }

    private static final Set<Caller> STAFF = EnumSet.of(Caller.TELLER, Caller.CHECKER, Caller.ADMIN);
    private static final Set<Caller> TELLER_OR_ADMIN = EnumSet.of(Caller.TELLER, Caller.ADMIN);
    private static final Set<Caller> ADMIN_ONLY = EnumSet.of(Caller.ADMIN);
    private static final Set<Caller> EVERYONE = EnumSet.allOf(Caller.class);

    private static final Map<Caller, String> TOKENS = new ConcurrentHashMap<>();
    private static volatile TestUser tokenUser;

    record Endpoint(HttpMethod method, String path, String body, Set<Caller> allowed) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestUsers testUsers;

    static Stream<Arguments> cases() {
        List<Endpoint> endpoints = List.of(
                new Endpoint(HttpMethod.POST, "/api/v1/auth/token", "TOKEN", EVERYONE),
                new Endpoint(HttpMethod.GET, "/actuator/health", null, EVERYONE),
                new Endpoint(HttpMethod.GET, "/actuator/prometheus", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/customers", "CUSTOMER", TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/customers/999999", null, STAFF),
                new Endpoint(HttpMethod.POST, "/api/v1/customers/999999/kyc/verify", null, TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.POST, "/api/v1/customers/999999/kyc/reject", "{\"reason\":\"Document expired\"}",
                        TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/customers/999999/accounts", null, STAFF),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts", "{\"customerId\":999999,\"type\":\"SAVINGS\"}",
                        TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/accounts/999999", null, STAFF),
                new Endpoint(HttpMethod.GET, "/api/v1/accounts/999999/balance", null, STAFF),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts/999999/freeze", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts/999999/unfreeze", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts/999999/close", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts/999999/deposits", "{\"amount\":10.00,\"currency\":\"INR\"}",
                        TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.POST, "/api/v1/accounts/999999/withdrawals",
                        "{\"amount\":10.00,\"currency\":\"INR\"}", TELLER_OR_ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/journals/999999", null, STAFF),
                new Endpoint(HttpMethod.POST, "/api/v1/journals/999999/reversal", "{\"reason\":\"Posted twice\"}",
                        ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/transfers", "{\"fromAccount\":\"000199999991\","
                        + "\"toAccount\":\"000199999992\",\"amount\":10.00,\"currency\":\"INR\"}", ADMIN_ONLY),
                new Endpoint(HttpMethod.GET, "/api/v1/transfers/999999", null, STAFF),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/users", "USER", ADMIN_ONLY),
                new Endpoint(HttpMethod.GET, "/api/v1/admin/users/999999", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.PUT, "/api/v1/admin/users/999999/customer", "{\"customerId\":1}", ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/users/999999/disable", null, ADMIN_ONLY),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/users/999999/enable", null, ADMIN_ONLY));
        return endpoints.stream().flatMap(endpoint -> Stream.of(Caller.values())
                .map(caller -> Arguments.of(endpoint, caller)));
    }

    @ParameterizedTest(name = "{1} calling {0}")
    @MethodSource("cases")
    void everyEndpointHonoursTheRoleMatrix(Endpoint endpoint, Caller caller) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(requestFor(endpoint, caller)).andReturn().getResponse();
        int status = response.getStatus();

        if (endpoint.allowed().contains(caller)) {
            assertThat(status).as("%s should be allowed on %s", caller, endpoint).isNotIn(401, 403);
        } else if (caller == Caller.ANONYMOUS) {
            assertThat(status).isEqualTo(401);
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        } else {
            assertThat(status).as("%s should be refused on %s", caller, endpoint).isEqualTo(403);
            assertProblem(response, 403, "ACCESS_DENIED");
        }
    }

    private MockHttpServletRequestBuilder requestFor(Endpoint endpoint, Caller caller) {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), endpoint.path());
        if (endpoint.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(bodyFor(endpoint.body()));
        }
        if (endpoint.path().equals("/api/v1/transfers")) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return caller == Caller.ANONYMOUS ? builder.with(anonymous()) : builder.with(TestUsers.bearer(tokenFor(caller)));
    }

    private String bodyFor(String body) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return switch (body) {
            case "TOKEN" -> "{\"username\":\"" + credentialsUser().username() + "\",\"password\":\""
                    + TestUsers.PASSWORD + "\"}";
            case "CUSTOMER" -> "{\"fullName\":\"Matrix Customer\",\"email\":\"matrix-" + unique
                    + "@example.com\",\"phone\":\"+919812345678\",\"dateOfBirth\":\"1990-01-01\"}";
            case "USER" -> "{\"username\":\"matrix-" + unique + "\",\"password\":\"" + TestUsers.PASSWORD
                    + "\",\"roles\":[\"TELLER\"]}";
            default -> body;
        };
    }

    private String tokenFor(Caller caller) {
        return TOKENS.computeIfAbsent(caller, c -> testUsers.tokenFor(testUsers.create(Role.valueOf(c.name()))));
    }

    private TestUser credentialsUser() {
        if (tokenUser == null) {
            tokenUser = testUsers.create(Role.TELLER);
        }
        return tokenUser;
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String errorCode)
            throws Exception {
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String body = response.getContentAsString();
        assertThat((int) JsonPath.read(body, "$.status")).isEqualTo(status);
        assertThat((String) JsonPath.read(body, "$.errorCode")).isEqualTo(errorCode);
        assertThat((String) JsonPath.read(body, "$.correlationId")).isEqualTo(response.getHeader("X-Correlation-Id"));
    }
}
