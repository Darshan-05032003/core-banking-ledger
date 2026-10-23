package com.darshan.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserTests {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void withoutARequestTheActorIsTheSystemWithNoRoles() {
        assertThat(currentUser.name()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(currentUser.roles()).isEmpty();
    }

    @Test
    void anUnauthenticatedTokenCountsAsNobody() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("asha", "secret"));

        assertThat(currentUser.name()).isEqualTo(CurrentUser.SYSTEM);
        assertThat(currentUser.roles()).isEmpty();
    }

    @Test
    void rolesAreTheRoleAuthoritiesWithoutThePrefixInSortedOrder() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "teller7", null, AuthorityUtils.createAuthorityList("ROLE_TELLER", "SCOPE_read", "ROLE_ADMIN")));

        assertThat(currentUser.name()).isEqualTo("teller7");
        assertThat(currentUser.roles()).isEqualTo(List.of("ADMIN", "TELLER"));
    }
}
