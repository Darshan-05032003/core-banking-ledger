package com.darshan.ledger.common.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// The clock that fires @Scheduled methods. Switched off with app.scheduling.enabled=false, for
// the test profile and for instances that should only serve requests; the job beans stay and can
// still be called directly.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
