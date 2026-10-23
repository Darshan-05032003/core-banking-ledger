package com.darshan.ledger.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Who is acting, for the created_by and audit columns. Scheduled jobs run without a request and
// are recorded as "system" with no roles.
@Component
public class CurrentUser {

    public static final String SYSTEM = "system";

    public String name() {
        Authentication authentication = authentication();
        return authentication == null ? SYSTEM : authentication.getName();
    }

    public List<String> roles() {
        Authentication authentication = authentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(Role.AUTHORITY_PREFIX))
                .map(authority -> authority.substring(Role.AUTHORITY_PREFIX.length()))
                .sorted()
                .toList();
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? null : authentication;
    }
}
