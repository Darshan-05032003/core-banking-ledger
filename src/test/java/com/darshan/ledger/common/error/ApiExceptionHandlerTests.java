package com.darshan.ledger.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The controller below is a nested class of a test, which Boot's test type-exclude filter keeps
// out of component scanning, so it is imported explicitly here and never reaches the real context.
@WebMvcTest(controllers = ApiExceptionHandlerTests.ErrorTriggerController.class)
@Import(ApiExceptionHandlerTests.ErrorTriggerController.class)
@WithMockUser
class ApiExceptionHandlerTests {

    private static final String CORRELATION_ID = "test-correlation-1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailureListsFieldErrors() throws Exception {
        mockMvc.perform(post("/test/errors/validation").with(csrf())
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.instance").value("/test/errors/validation"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].message").value("must be greater than 0"));
    }

    @Test
    void validRequestPassesThrough() throws Exception {
        mockMvc.perform(post("/test/errors/validation").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rent\",\"amount\":10.00}"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonIsRejectedWithoutLeakingParserDetails() throws Exception {
        mockMvc.perform(post("/test/errors/validation").with(csrf())
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
    }

    @Test
    void missingResourceReturns404WithErrorCode() throws Exception {
        mockMvc.perform(get("/test/errors/not-found").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Account 42 not found"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void unknownPathReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/no/such/path").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void conflictReturns409() throws Exception {
        mockMvc.perform(get("/test/errors/conflict").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Account already closed"))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void optimisticLockFailureReturns409WithRetryHint() throws Exception {
        mockMvc.perform(get("/test/errors/stale-update"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The record was modified by another request, please retry"));
    }

    @Test
    void lockTimeoutReturns409WithItsOwnErrorCode() throws Exception {
        mockMvc.perform(get("/test/errors/lock-timeout"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOCK_TIMEOUT"))
                .andExpect(jsonPath("$.detail").value("The account is busy with another operation, please retry"))
                .andExpect(content().string(not(containsString("canceling statement"))));
    }

    @Test
    void databaseConstraintViolationReturns409WithoutTheDatabaseMessage() throws Exception {
        mockMvc.perform(get("/test/errors/constraint").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(content().string(not(containsString("dup@example.com"))));
    }

    @Test
    void businessRuleViolationReturns422WithTheRulesErrorCode() throws Exception {
        mockMvc.perform(get("/test/errors/business-rule").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.detail").value("Available balance is lower than the transfer amount"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void rateLimitReturns429WithRetryAfterInWholeSecondsRoundedUp() throws Exception {
        mockMvc.perform(get("/test/errors/rate-limited").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void accessDeniedInsideAControllerReturns403NotInternalError() throws Exception {
        mockMvc.perform(get("/test/errors/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/unexpected")
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException")
                .doesNotContain("stackTrace");
    }

    @Test
    void generatedCorrelationIdMatchesTheResponseHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andReturn();

        String headerValue = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"" + headerValue + "\"");
    }

    enum TestErrorCode implements ErrorCode {
        INSUFFICIENT_FUNDS;

        @Override
        public String code() {
            return name();
        }
    }

    record Payment(@NotBlank String name, @Positive BigDecimal amount) {
    }

    @RestController
    @RequestMapping("/test/errors")
    static class ErrorTriggerController {

        @PostMapping("/validation")
        void validation(@Valid @RequestBody Payment payment) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Account", 42);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("Account already closed");
        }

        @GetMapping("/stale-update")
        void staleUpdate() {
            throw new OptimisticLockingFailureException("row version changed");
        }

        @GetMapping("/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("could not lock row",
                    new IllegalStateException("ERROR: canceling statement due to lock timeout"));
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint: Key (email)=(dup@example.com)");
        }

        @GetMapping("/business-rule")
        void businessRule() {
            throw new BusinessRuleException(TestErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance is lower than the transfer amount");
        }

        @GetMapping("/rate-limited")
        void rateLimited() {
            throw new RateLimitExceededException(Duration.ofMillis(2_500));
        }

        @GetMapping("/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("not the owner");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("database password is hunter2");
        }
    }
}
