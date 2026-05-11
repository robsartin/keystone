package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

  @Test
  @DisplayName("uses incoming X-Correlation-Id header and echoes it back")
  void shouldUseIncomingHeaderWhenPresent() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    req.addHeader("X-Correlation-Id", "abc-123");
    FilterChain chain =
        (request, response) -> assertThat(MDC.get("correlationId")).isEqualTo("abc-123");

    new CorrelationIdFilter().doFilter(req, res, chain);

    assertThat(res.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  @DisplayName("generates a correlation id when none is supplied")
  void shouldGenerateIdWhenHeaderMissing() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain = (request, response) -> assertThat(MDC.get("correlationId")).isNotBlank();

    new CorrelationIdFilter().doFilter(req, res, chain);

    assertThat(res.getHeader("X-Correlation-Id")).isNotBlank();
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  @DisplayName("clears MDC even if the filter chain throws")
  void shouldClearMdcWhenChainThrows() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain =
        (request, response) -> {
          throw new RuntimeException("boom");
        };

    try {
      new CorrelationIdFilter().doFilter(req, res, chain);
    } catch (Exception expected) {
      // expected
    }

    assertThat(MDC.get("correlationId")).isNull();
  }
}
