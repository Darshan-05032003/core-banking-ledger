package com.darshan.ledger.transfer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.FixedClockConfiguration;
import com.darshan.ledger.security.SecurityProperties;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// The real limiter with a tiny limit and the fixed test clock, so the bucket never refills on
// its own and the third request in a row is deterministic.
@WebMvcTest(TransferController.class)
@Import({TransferRateLimiter.class, FixedClockConfiguration.class})
@EnableConfigurationProperties(SecurityProperties.class)
@TestPropertySource(properties = "app.security.rate-limit.transfers-per-minute=2")
@WithMockUser(username = "asha", roles = "ADMIN")
class TransferRateLimitTests {

    private static final String BODY = "{\"fromAccount\":\"000100000023\",\"toAccount\":\"000100000057\","
            + "\"amount\":250.00,\"currency\":\"INR\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Test
    void theThirdTransferWithinTheMinuteIs429WithRetryAfter() throws Exception {
        given(transferService.transfer(any(), any())).willReturn(new TransferOutcome.Created(response()));

        transfer().andExpect(status().isCreated());
        transfer().andExpect(status().isCreated());
        transfer()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30))
                .andExpect(jsonPath("$.detail").value("Too many requests, retry after 30 seconds"))
                .andExpect(jsonPath("$.correlationId").exists());

        verify(transferService, times(2)).transfer(any(), any());
    }

    private org.springframework.test.web.servlet.ResultActions transfer() throws Exception {
        return mockMvc.perform(post("/api/v1/transfers").with(csrf())
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private static TransferResponse response() {
        Instant now = Instant.parse("2026-10-22T10:15:30Z");
        return new TransferResponse(12L, "TRF-0000000012", TransferStatus.COMPLETED, 23L, "XXXXXXXX0023", 57L,
                "XXXXXXXX0057", new BigDecimal("250.00"), "INR", null, 40L, "JRN-0000000040", null, null, "asha",
                now, now);
    }
}
