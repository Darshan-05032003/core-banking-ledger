package com.darshan.ledger.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

// The generated document is a contract a client developer reads before any code is written, so
// what it promises is checked here: the bearer scheme on every operation but the login, a tag per
// module, a summary on every operation, the required Idempotency-Key, and the shared error shape
// with an errorCode example for every status the API can answer with.
@IntegrationTest
class OpenApiDocumentTests {

    private static final List<String> TAGS = List.of("Auth", "Customers", "Accounts", "Teller", "Transfers",
            "Approvals", "Statements", "Journals", "Admin", "Jobs");

    @Autowired
    private MockMvc mockMvc;

    private DocumentContext document;

    @BeforeEach
    void loadDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs").with(anonymous()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        document = JsonPath.parse(body);
    }

    @Test
    void theSwaggerUiIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui.html").with(anonymous()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void declaresABearerJwtSchemeThatAppliesToEveryOperationExceptTheLogin() {
        assertThat(document.read("$.components.securitySchemes.bearerAuth.type", String.class)).isEqualTo("http");
        assertThat(document.read("$.components.securitySchemes.bearerAuth.scheme", String.class)).isEqualTo("bearer");
        assertThat(document.read("$.components.securitySchemes.bearerAuth.bearerFormat", String.class))
                .isEqualTo("JWT");
        assertThat(document.read("$.security[0].bearerAuth", List.class)).isEmpty();

        assertThat(document.read("$.paths['/api/v1/auth/token'].post.security", List.class)).isEmpty();
        assertThat(document.read("$.paths['/api/v1/auth/token'].post.responses['401'].$ref", String.class))
                .isEqualTo("#/components/responses/InvalidCredentials");
        assertThat(document.read("$.paths['/api/v1/auth/token'].post.responses", Map.class))
                .doesNotContainKey("403");
    }

    @Test
    void hasOneTagPerModuleAndEveryOperationIsSummarisedUnderOneOfThem() {
        List<String> tagNames = document.read("$.tags[*].name");
        assertThat(tagNames).containsExactlyElementsOf(TAGS);

        Map<String, Map<String, Map<String, Object>>> paths = document.read("$.paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path, methods) -> methods.forEach((method, operation) -> {
            String where = method.toUpperCase() + " " + path;
            assertThat(operation.get("operationId")).as("operationId of " + where).isInstanceOf(String.class);
            assertThat(operation.get("summary")).as("summary of " + where).isInstanceOf(String.class);
            assertThat((List<?>) operation.get("tags")).as("tags of " + where).hasSize(1);
            assertThat(TAGS).contains((String) ((List<?>) operation.get("tags")).getFirst());
            @SuppressWarnings("unchecked")
            Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses).as("responses of " + where).containsKeys("400", "401");
        }));
    }

    @Test
    void documentsTheIdempotencyKeyAsARequiredHeaderAndBothTransferOutcomes() {
        Map<String, Object> transfer = document.read("$.paths['/api/v1/transfers'].post");
        List<Map<String, Object>> parameters = document.read("$.paths['/api/v1/transfers'].post.parameters");
        Map<String, Object> header = parameters.stream()
                .filter(parameter -> "Idempotency-Key".equals(parameter.get("name")))
                .findFirst().orElseThrow();
        assertThat(header).containsEntry("in", "header").containsEntry("required", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) transfer.get("responses");
        assertThat(responses.keySet()).containsExactlyInAnyOrder("201", "202", "400", "401", "403", "404", "409",
                "422", "429");
        assertThat(document.read("$.paths['/api/v1/transfers'].post.responses['201'].content"
                + "['application/json'].examples.createTransfer201.value.status", String.class))
                .isEqualTo("COMPLETED");
        assertThat(document.read("$.paths['/api/v1/transfers'].post.responses['202'].content"
                + "['application/json'].examples.createTransfer202.value.status", String.class))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(document.read("$.paths['/api/v1/transfers'].post.requestBody.content['application/json']"
                + ".examples.createTransfer.value.fromAccount", String.class)).isEqualTo("000100000421");
    }

    @Test
    void carriesWorkedExamplesForTheMainFlows() {
        assertThat(document.read("$.paths['/api/v1/auth/token'].post.requestBody.content['application/json']"
                + ".examples.issueToken.value.username", String.class)).isEqualTo("teller.priya");
        assertThat(document.read("$.paths['/api/v1/customers'].post.responses['201'].content['application/json']"
                + ".examples.onboardCustomer201.value.kycStatus", String.class)).isEqualTo("PENDING");
        assertThat(document.read("$.paths['/api/v1/accounts'].post.requestBody.content['application/json']"
                + ".examples.openAccount.value.type", String.class)).isEqualTo("SAVINGS");
        assertThat(document.read("$.paths['/api/v1/accounts/{accountId}/deposits'].post.responses['201']"
                + ".content['application/json'].examples.deposit201.value.journal.type", String.class))
                .isEqualTo("DEPOSIT");
        assertThat(document.read("$.paths['/api/v1/approvals/{transferId}/approve'].post.responses['200']"
                + ".content['application/json'].examples.approveTransfer200.value.decidedBy", String.class))
                .isEqualTo("checker.rahul");
    }

    @Test
    void describesEveryErrorStatusAsAProblemDocumentWithAnErrorCodeExample() {
        Map<String, Map<String, Object>> responses = document.read("$.components.responses");
        assertThat(responses.keySet()).containsExactlyInAnyOrder("BadRequest", "InvalidCredentials", "Unauthorized",
                "Forbidden", "NotFound", "Conflict", "UnprocessableEntity", "TooManyRequests");
        responses.forEach((name, response) -> {
            String schemaRef = document.read("$.components.responses." + name
                    + ".content['application/problem+json'].schema.$ref");
            assertThat(schemaRef).as(name).isEqualTo("#/components/schemas/ProblemDetail");
            List<String> errorCodes = document.read("$.components.responses." + name
                    + ".content['application/problem+json'].examples[*].value.errorCode");
            assertThat(errorCodes).as("errorCode example of " + name).hasSize(1);
        });
        assertThat(document.read("$.components.responses.UnprocessableEntity.content['application/problem+json']"
                + ".examples.insufficientFunds.value.errorCode", String.class)).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(document.read("$.components.responses.BadRequest.content['application/problem+json']"
                + ".examples.validationFailed.value.errors[0].field", String.class)).isEqualTo("amount");
        assertThat(document.read("$.components.responses.TooManyRequests.headers['Retry-After']", Map.class))
                .isNotEmpty();
        assertThat(document.read("$.components.schemas.ProblemDetail.properties", Map.class))
                .containsKeys("errorCode", "correlationId", "errors");
    }
}
