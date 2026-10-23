package com.darshan.ledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.darshan.ledger.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class DevAdminSeederTests {

    private final AppUserService appUserService = mock(AppUserService.class);

    @ParameterizedTest(name = "username ''{0}'' password ''{1}''")
    @CsvSource({"admin, ''", "'', secret-enough-for-dev", "' ', secret-enough-for-dev"})
    void withoutBothAUsernameAndAPasswordNothingIsSeeded(String username, String password) {
        new DevAdminSeeder(appUserService, new DevAdminProperties(username, password))
                .run(new DefaultApplicationArguments());

        verifyNoInteractions(appUserService);
    }

    @Test
    void anExistingLoginIsNeverOverwritten() {
        given(appUserService.exists("admin")).willReturn(true);

        new DevAdminSeeder(appUserService, new DevAdminProperties("admin", "secret-enough-for-dev"))
                .run(new DefaultApplicationArguments());

        verify(appUserService, never()).create(any());
    }

    @Test
    void aMissingAdminIsCreatedWithTheAdminRoleAndNoCustomer() {
        given(appUserService.exists("admin")).willReturn(false);

        new DevAdminSeeder(appUserService, new DevAdminProperties("admin", "secret-enough-for-dev"))
                .run(new DefaultApplicationArguments());

        ArgumentCaptor<CreateUserRequest> request = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(appUserService).create(request.capture());
        assertThat(request.getValue().username()).isEqualTo("admin");
        assertThat(request.getValue().password()).isEqualTo("secret-enough-for-dev");
        assertThat(request.getValue().roles()).containsExactly(Role.ADMIN);
        assertThat(request.getValue().customerId()).isNull();
    }
}
