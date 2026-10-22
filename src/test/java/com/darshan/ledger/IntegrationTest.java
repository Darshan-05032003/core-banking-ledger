package com.darshan.ledger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

// One shared application context for every full-stack test: real PostgreSQL in a container, a
// fixed clock and a mock ADMIN user (who may call everything), so the context and the container
// start once. Tests about roles and ownership obtain real tokens instead.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, TestAccounts.class})
@WithMockUser(roles = "ADMIN")
public @interface IntegrationTest {
}
