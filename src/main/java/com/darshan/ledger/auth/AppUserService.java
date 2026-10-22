package com.darshan.ledger.auth;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.customer.CustomerService;
import com.darshan.ledger.security.CurrentUser;
import com.darshan.ledger.security.Role;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AppUserService {

    private static final Logger log = LoggerFactory.getLogger(AppUserService.class);

    private final AppUserRepository appUserRepository;
    private final CustomerService customerService;
    private final PasswordEncoder passwordEncoder;
    private final AppUserMapper appUserMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AppUserService(AppUserRepository appUserRepository, CustomerService customerService,
            PasswordEncoder passwordEncoder, AppUserMapper appUserMapper, CurrentUser currentUser, Clock clock) {
        this.appUserRepository = appUserRepository;
        this.customerService = customerService;
        this.passwordEncoder = passwordEncoder;
        this.appUserMapper = appUserMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public AppUserResponse create(CreateUserRequest request) {
        String username = normalise(request.username());
        if (appUserRepository.existsByUsername(username)) {
            throw new ConflictException(AuthErrorCode.USERNAME_TAKEN, "Username " + username + " is already taken");
        }
        if (request.customerId() != null) {
            requireLinkable(request.roles().contains(Role.CUSTOMER), username, request.customerId());
        }
        AppUser user = appUserRepository.save(new AppUser(username, passwordEncoder.encode(request.password()),
                request.roles(), request.customerId(), currentUser.name(), clock.instant()));
        log.info("User {} created with roles {}", username, request.roles());
        return appUserMapper.toResponse(user);
    }

    public AppUserResponse linkCustomer(Long userId, LinkCustomerRequest request) {
        AppUser user = requireUser(userId);
        requireLinkable(user.hasRole(Role.CUSTOMER), user.getUsername(), request.customerId());
        user.linkCustomer(request.customerId());
        log.info("User {} linked to customer {}", user.getUsername(), request.customerId());
        return appUserMapper.toResponse(user);
    }

    public AppUserResponse disable(Long userId) {
        AppUser user = requireUser(userId);
        user.disable();
        log.info("User {} disabled", user.getUsername());
        return appUserMapper.toResponse(user);
    }

    public AppUserResponse enable(Long userId) {
        AppUser user = requireUser(userId);
        user.enable();
        log.info("User {} enabled", user.getUsername());
        return appUserMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public AppUserResponse get(Long userId) {
        return appUserMapper.toResponse(requireUser(userId));
    }

    @Transactional(readOnly = true)
    public boolean exists(String username) {
        return appUserRepository.existsByUsername(normalise(username));
    }

    // Only a CUSTOMER login may act for a customer, the customer must exist, and a customer has at
    // most one login; the unique constraint on customer_id backs the last rule under concurrency.
    private void requireLinkable(boolean hasCustomerRole, String username, Long customerId) {
        if (!hasCustomerRole) {
            throw new BusinessRuleException(AuthErrorCode.CUSTOMER_ROLE_REQUIRED,
                    "User " + username + " needs the CUSTOMER role to be linked to a customer");
        }
        customerService.requireCustomer(customerId);
        if (appUserRepository.existsByCustomerId(customerId)) {
            throw new ConflictException(AuthErrorCode.CUSTOMER_ALREADY_LINKED,
                    "Customer " + customerId + " already has a login");
        }
    }

    private AppUser requireUser(Long userId) {
        return appUserRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private static String normalise(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
