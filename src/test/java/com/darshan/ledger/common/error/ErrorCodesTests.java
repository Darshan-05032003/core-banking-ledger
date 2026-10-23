package com.darshan.ledger.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

// The audit trail and the scheduled jobs record failures by code without rendering a response,
// so this mapping must agree with what the exception handler tells the client.
class ErrorCodesTests {

    static Stream<Arguments> applicationFailures() {
        return Stream.of(
                arguments(new BusinessRuleException(CommonErrorCode.VALIDATION_FAILED, "rule")),
                arguments(new ResourceNotFoundException("Account", 7L)),
                arguments(new ConflictException(CommonErrorCode.CONFLICT, "state")),
                arguments(new RateLimitExceededException(Duration.ofSeconds(3))));
    }

    @ParameterizedTest
    @MethodSource("applicationFailures")
    void anApplicationFailureReportsItsOwnCode(LedgerException failure) {
        assertThat(ErrorCodes.of(failure)).isSameAs(failure.getErrorCode());
    }

    static Stream<Arguments> frameworkFailures() {
        return Stream.of(
                arguments(new AccessDeniedException("not yours"), CommonErrorCode.ACCESS_DENIED),
                arguments(new CannotAcquireLockException("lock_timeout"), CommonErrorCode.LOCK_TIMEOUT),
                arguments(new OptimisticLockingFailureException("stale"), CommonErrorCode.CONFLICT),
                arguments(new DataIntegrityViolationException("duplicate"), CommonErrorCode.CONFLICT),
                arguments(new IllegalStateException("bug"), CommonErrorCode.INTERNAL_ERROR),
                arguments(new NullPointerException(), CommonErrorCode.INTERNAL_ERROR));
    }

    @ParameterizedTest
    @MethodSource("frameworkFailures")
    void frameworkAndUnexpectedFailuresMapToTheCommonCodes(Throwable failure, CommonErrorCode expected) {
        assertThat(ErrorCodes.of(failure)).isEqualTo(expected);
    }
}
