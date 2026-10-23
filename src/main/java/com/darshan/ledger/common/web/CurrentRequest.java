package com.darshan.ledger.common.web;

import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// The one place outside the controllers that looks at the servlet request. Code that runs on a
// request thread (the audit aspect) asks here instead of reaching into the web layer itself, and
// gets an empty answer on a scheduler or Kafka thread.
public final class CurrentRequest {

    private CurrentRequest() {
    }

    public static Optional<String> clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.ofNullable(attributes.getRequest().getRemoteAddr());
        }
        return Optional.empty();
    }
}
