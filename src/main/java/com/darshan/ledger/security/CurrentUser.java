package com.darshan.ledger.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Who is acting, for the created_by and audit columns. Scheduled jobs run without a request and
// are recorded as "system".
@Component
public class CurrentUser {

    public static final String SYSTEM = "system";

    public String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return SYSTEM;
        }
        return authentication.getName();
    }
}
