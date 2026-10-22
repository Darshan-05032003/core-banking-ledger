package com.darshan.ledger.auth;

import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    // A plain id rather than a relation: the login store only needs to know which customer a
    // CUSTOMER user acts for, and the link is validated by the service when it is made.
    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AppUser(String username, String passwordHash, Set<Role> roles, Long customerId, String createdBy,
            Instant createdAt) {
        this.username = Objects.requireNonNull(username);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.roles = EnumSet.copyOf(roles);
        this.customerId = customerId;
        this.enabled = true;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void linkCustomer(Long customerId) {
        this.customerId = Objects.requireNonNull(customerId);
    }

    public void disable() {
        if (!enabled) {
            throw new ConflictException(AuthErrorCode.USER_STATUS_UNCHANGED, "User " + username + " is already disabled");
        }
        enabled = false;
    }

    public void enable() {
        if (enabled) {
            throw new ConflictException(AuthErrorCode.USER_STATUS_UNCHANGED, "User " + username + " is already enabled");
        }
        enabled = true;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AppUser user && id != null && id.equals(user.id));
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
