package com.darshan.ledger.common.error;

import com.darshan.ledger.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

// The one place that turns a status, an error code and a message into the problem body this API
// returns, used by the exception handler and by anything that stores a response for later replay.
public final class ProblemDetails {

    public static final String ERROR_CODE = "errorCode";
    public static final String CORRELATION_ID = "correlationId";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, ErrorCode errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty(ERROR_CODE, errorCode.code());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }

    public static ProblemDetail of(LedgerException exception) {
        ProblemDetail problem = of(exception.getStatus(), exception.getErrorCode(), exception.getMessage());
        exception.extensions().forEach(problem::setProperty);
        return problem;
    }
}
