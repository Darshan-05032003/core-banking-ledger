package com.darshan.ledger.auth;

import com.darshan.ledger.security.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// What the authentication manager sees: the stored hash, the roles and, for a CUSTOMER login, the
// customer it acts for. The entity itself stays out of the security context.
public record AppUserPrincipal(Long id, String username, String passwordHash, boolean enabled, Set<Role> roles,
        Long customerId) implements UserDetails {

    static AppUserPrincipal of(AppUser user) {
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                user.getRoles(), user.getCustomerId());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(Role::authority).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public List<String> roleNames() {
        return roles.stream().map(Role::name).sorted().toList();
    }

    public Optional<Long> linkedCustomerId() {
        return Optional.ofNullable(customerId);
    }
}
