package com.darshan.ledger.auth;

import com.darshan.ledger.security.Role;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// A fresh local database has no users, so nobody could call the admin endpoints to create one.
// The dev profile seeds a single ADMIN from the environment; the hash is computed here, so the
// plain password exists only in the developer's shell.
@Component
@Profile("dev")
class DevAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    private final AppUserService appUserService;
    private final DevAdminProperties properties;

    DevAdminSeeder(AppUserService appUserService, DevAdminProperties properties) {
        this.appUserService = appUserService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.username()) || !StringUtils.hasText(properties.password())) {
            log.warn("APP_ADMIN_PASSWORD is not set; no dev admin user was seeded");
            return;
        }
        if (appUserService.exists(properties.username())) {
            log.info("Dev admin user {} already exists", properties.username());
            return;
        }
        appUserService.create(new CreateUserRequest(properties.username(), properties.password(),
                Set.of(Role.ADMIN), null));
        log.info("Dev admin user {} seeded", properties.username());
    }
}
