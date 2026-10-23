package com.darshan.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestUsers;
import com.darshan.ledger.ledger.EndOfDayCheck;
import com.darshan.ledger.notification.NotificationMetrics;
import com.darshan.ledger.outbox.OutboxMetrics;
import com.darshan.ledger.transfer.TransferMetrics;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// What an operator, a load balancer and a Prometheus scraper may see, and what they may not.
// Health is open but tells an anonymous caller only UP or DOWN; the components and their details
// appear for an ADMIN. The scrape endpoint and everything else under /actuator need ADMIN, and
// endpoints that are not exposed do not exist even for an ADMIN.
@IntegrationTest
class ActuatorSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestUsers testUsers;

    @Test
    void anonymousCallersSeeOnlyTheOverallHealthStatus() throws Exception {
        mockMvc.perform(get("/actuator/health").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void anAdminSeesTheDatabaseAndKafkaComponentsWithDetails() throws Exception {
        mockMvc.perform(get("/actuator/health").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.db.details.database").value("PostgreSQL"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.details.brokers").value(1))
                .andExpect(jsonPath("$.components.kafka.details.clusterId").isString());
    }

    @Test
    void aCustomerIsAuthenticatedButNotEntitledToTheDetails() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(TestUsers.bearer(testUsers.tokenFor(testUsers.createUnlinkedCustomer()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void theProbesAreOpenAndReadinessWaitsForTheDatabaseAndKafka() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"));
    }

    @Test
    void infoIsOpenAndCarriesTheBuildCoordinates() throws Exception {
        mockMvc.perform(get("/actuator/info").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value("core-banking-ledger"))
                .andExpect(jsonPath("$.build.group").value("com.darshan"))
                .andExpect(jsonPath("$.build.version").isString())
                .andExpect(jsonPath("$.build.time").isString());
    }

    @Test
    void theScrapeEndpointNeedsAnAdmin() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .with(TestUsers.bearer(testUsers.tokenFor(testUsers.createUnlinkedCustomer()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theScrapeExposesTheApplicationMetricsInPrometheusForm() throws Exception {
        String scrape = mockMvc.perform(get("/actuator/prometheus").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains(prometheusName(TransferMetrics.COMPLETED) + "_total")
                .contains(prometheusName(TransferMetrics.FAILED) + "_total{errorCode=\"DAILY_LIMIT_EXCEEDED\"")
                .contains(prometheusName(TransferMetrics.LATENCY) + "_seconds_bucket{")
                .contains(prometheusName(TransferMetrics.LATENCY) + "_seconds_count{")
                .contains(prometheusName(OutboxMetrics.LAG) + "_seconds ")
                .contains(prometheusName(OutboxMetrics.UNPUBLISHED) + " ")
                .contains(prometheusName(NotificationMetrics.SENT) + "_total{channel=\"SMS\"")
                .contains(prometheusName(NotificationMetrics.DEAD_LETTERED) + "_total")
                .contains(prometheusName(EndOfDayCheck.MISMATCH_METRIC) + "_total{type=\"balance\"")
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds_count");
    }

    @Test
    void endpointsThatAreNotExposedDoNotExistEvenForAnAdmin() throws Exception {
        mockMvc.perform(get("/actuator/env").with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")
                        .with(TestUsers.bearer(testUsers.tokenFor(testUsers.createUnlinkedCustomer()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/metrics").with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.env").doesNotExist())
                .andExpect(jsonPath("$._links.prometheus.href").value(Matchers.endsWith("/actuator/prometheus")));
    }

    private RequestPostProcessor admin() {
        return TestUsers.bearer(testUsers.tokenFor(testUsers.create(Role.ADMIN)));
    }

    private static String prometheusName(String meterName) {
        return meterName.replace('.', '_');
    }
}
