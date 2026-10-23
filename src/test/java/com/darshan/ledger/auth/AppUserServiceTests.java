package com.darshan.ledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import com.darshan.ledger.customer.CustomerService;
import com.darshan.ledger.security.CurrentUser;
import com.darshan.ledger.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppUserServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final CustomerService customerService = mock(CustomerService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AppUserService service = new AppUserService(repository, customerService, passwordEncoder,
            new AppUserMapperImpl(), currentUser, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createNormalisesTheUsernameAndStoresOnlyTheHash() {
        given(currentUser.name()).willReturn("admin");
        given(passwordEncoder.encode("correct-horse-battery")).willReturn("{bcrypt}hash");
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        AppUserResponse response = service.create(new CreateUserRequest(" Asha.Rao ", "correct-horse-battery",
                Set.of(Role.TELLER, Role.CHECKER), null));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("asha.rao");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(saved.getValue().getRoles()).containsExactlyInAnyOrder(Role.TELLER, Role.CHECKER);
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(response.username()).isEqualTo("asha.rao");
        assertThat(response.customerId()).isNull();
    }

    @Test
    void getReturnsTheMappedLoginAndAnUnknownIdIs404() {
        AppUser user = new AppUser("asha", "{bcrypt}hash", Set.of(Role.CUSTOMER), 42L, "admin", NOW);
        ReflectionTestUtils.setField(user, "id", 5L);
        given(repository.findById(5L)).willReturn(Optional.of(user));
        given(repository.findById(6L)).willReturn(Optional.empty());

        AppUserResponse response = service.get(5L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.username()).isEqualTo("asha");
        assertThat(response.customerId()).isEqualTo(42L);
        assertThatThrownBy(() -> service.get(6L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void existsLooksUpTheNormalisedUsername() {
        given(repository.existsByUsername("asha.rao")).willReturn(true);

        assertThat(service.exists(" Asha.Rao ")).isTrue();
        assertThat(service.exists("ravi")).isFalse();
    }

    @Test
    void aTakenUsernameIsAConflict() {
        given(repository.existsByUsername("asha")).willReturn(true);

        assertThatThrownBy(() -> service.create(request("asha", Set.of(Role.TELLER), null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.USERNAME_TAKEN));
        verify(repository, never()).save(any());
    }

    @Test
    void onlyACustomerLoginCanBeLinkedToACustomer() {
        assertThatThrownBy(() -> service.create(request("teller1", Set.of(Role.TELLER), 7L)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.CUSTOMER_ROLE_REQUIRED));
        verify(repository, never()).save(any());
    }

    @Test
    void theCustomerMustExistAndMayHaveOnlyOneLogin() {
        given(customerService.requireCustomer(404L)).willThrow(new ResourceNotFoundException("Customer", 404L));
        given(repository.existsByCustomerId(7L)).willReturn(true);

        assertThatThrownBy(() -> service.create(request("asha", Set.of(Role.CUSTOMER), 404L)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(request("asha", Set.of(Role.CUSTOMER), 7L)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.CUSTOMER_ALREADY_LINKED));
        verify(repository, never()).save(any());
    }

    @Test
    void linkingSetsTheCustomerOnAnExistingCustomerLogin() {
        AppUser user = new AppUser("asha", "{bcrypt}hash", Set.of(Role.CUSTOMER), null, "admin", NOW);
        given(repository.findById(3L)).willReturn(Optional.of(user));

        AppUserResponse response = service.linkCustomer(3L, new LinkCustomerRequest(9L));

        assertThat(user.getCustomerId()).isEqualTo(9L);
        assertThat(response.customerId()).isEqualTo(9L);
        verify(customerService).requireCustomer(9L);
    }

    @Test
    void disablingTwiceIsAConflictAndEnablingRestoresTheLogin() {
        AppUser user = new AppUser("asha", "{bcrypt}hash", Set.of(Role.TELLER), null, "admin", NOW);
        given(repository.findById(3L)).willReturn(Optional.of(user));

        assertThat(service.disable(3L).enabled()).isFalse();
        assertThatThrownBy(() -> service.disable(3L))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.USER_STATUS_UNCHANGED));
        assertThat(service.enable(3L).enabled()).isTrue();
        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static CreateUserRequest request(String username, Set<Role> roles, Long customerId) {
        return new CreateUserRequest(username, "correct-horse-battery", roles, customerId);
    }
}
