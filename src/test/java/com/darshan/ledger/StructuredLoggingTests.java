package com.darshan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The prod profile switches the console to Elastic Common Schema JSON through Boot's own encoder;
// dev and test keep the readable pattern. Both carry the correlation id: the pattern through
// logging.pattern.correlation, the JSON because every MDC entry becomes a top-level field. The
// encoder is exercised directly here so the assertion is about the real formatter, without
// reconfiguring the logging of the test JVM.
class StructuredLoggingTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theProdProfileWritesEcsJsonToTheConsole() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(environment.getProperty("logging.pattern.correlation")).contains("%X{correlationId");
        });
    }

    @Test
    void devAndTestKeepTheReadablePatternWithTheCorrelationId() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                Environment environment = context.getEnvironment();
                assertThat(environment.getProperty("logging.structured.format.console")).as(profile).isNull();
                assertThat(environment.getProperty("logging.pattern.correlation")).as(profile)
                        .isEqualTo("[%X{correlationId:-}] ");
            });
        }
    }

    @Test
    void anEcsLineCarriesTheCorrelationIdTheLevelTheLoggerAndTheServiceName() {
        LoggerContext loggerContext = new LoggerContext();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "core-banking-ledger");
        loggerContext.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat("ecs");
        encoder.start();

        LoggingEvent event = new LoggingEvent(getClass().getName(),
                loggerContext.getLogger("com.darshan.ledger.transfer.TransferProcessor"), Level.INFO,
                "Transfer TRF-0000000012 completed", null, null);
        event.setMDCPropertyMap(Map.of("correlationId", "req-42"));
        JsonNode line = new ObjectMapper().readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));

        assertThat(line.path("correlationId").asString()).isEqualTo("req-42");
        assertThat(line.path("message").asString()).isEqualTo("Transfer TRF-0000000012 completed");
        assertThat(line.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(line.path("log").path("logger").asString()).isEqualTo("com.darshan.ledger.transfer.TransferProcessor");
        assertThat(line.path("service").path("name").asString()).isEqualTo("core-banking-ledger");
        assertThat(line.has("@timestamp")).isTrue();
        assertThat(line.path("ecs").path("version").asString()).isNotBlank();
    }
}
