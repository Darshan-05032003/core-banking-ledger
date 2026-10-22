package com.darshan.ledger.security;

import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 401: no usable credentials. The WWW-Authenticate header follows RFC 6750; the body is the same
// problem shape as every other error of this API, so clients handle one format.
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);

    private final ProblemResponseWriter writer;

    public ProblemDetailAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        Rejection rejection = classify(exception);
        log.info("Unauthenticated request to {} {}: {}", request.getMethod(), request.getRequestURI(),
                rejection.errorCode().code());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, rejection.challenge());
        writer.write(request, response,
                ProblemDetails.of(HttpStatus.UNAUTHORIZED, rejection.errorCode(), rejection.detail()));
    }

    private static Rejection classify(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth) {
            String description = oauth.getError().getDescription();
            if (description != null && description.contains("expired")) {
                return new Rejection(SecurityErrorCode.TOKEN_EXPIRED, "The access token has expired",
                        "Bearer error=\"invalid_token\", error_description=\"The access token has expired\"");
            }
            return new Rejection(SecurityErrorCode.INVALID_TOKEN, "The access token is invalid",
                    "Bearer error=\"invalid_token\", error_description=\"The access token is invalid\"");
        }
        return new Rejection(SecurityErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required", "Bearer");
    }

    private record Rejection(ErrorCode errorCode, String detail, String challenge) {
    }
}
