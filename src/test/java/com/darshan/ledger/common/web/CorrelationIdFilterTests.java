package com.darshan.ledger.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTests {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesTheCorrelationIdSentByTheCaller() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(CorrelationIdFilter.HEADER, "req-2024-10-22.0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        assertThat(seenInsideChain.get()).isEqualTo("req-2024-10-22.0001");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("req-2024-10-22.0001");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesACorrelationIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "new\nline", "semi;colon", "<script>"})
    void replacesAHeaderValueThatIsNotAPlainToken(String unsafeValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(CorrelationIdFilter.HEADER, unsafeValue);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(echoed).isNotEqualTo(unsafeValue);
        assertThat(UUID.fromString(echoed)).isNotNull();
    }

    @Test
    void replacesAnOverlongHeaderValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }

    @Test
    void clearsTheMdcEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(CorrelationIdFilter.HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
