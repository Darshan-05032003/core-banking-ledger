package com.darshan.ledger.security;

import com.darshan.ledger.common.error.CommonErrorCode;
import com.darshan.ledger.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// 403 decided by the URL rules of the filter chain. Denials decided by @PreAuthorize happen inside
// the controller invocation and are rendered by the controller advice with the same body.
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);

    private final ProblemResponseWriter writer;

    public ProblemDetailAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.info("Access denied for {} {}", request.getMethod(), request.getRequestURI());
        writer.write(request, response,
                ProblemDetails.of(HttpStatus.FORBIDDEN, CommonErrorCode.ACCESS_DENIED, "Access denied"));
    }
}
