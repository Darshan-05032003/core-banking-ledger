package com.darshan.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-10-22T10:15:30Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Bean
    @Primary
    MutableClock fixedClock() {
        return new MutableClock(NOW, ZoneOffset.UTC);
    }
}
