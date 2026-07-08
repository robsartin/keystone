package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

@DisplayName("HtmxAuthenticationEntryPoint")
class HtmxAuthenticationEntryPointTest {

  private final AuthenticationEntryPoint fallback = Mockito.mock(AuthenticationEntryPoint.class);
  private final HtmxAuthenticationEntryPoint entryPoint =
      new HtmxAuthenticationEntryPoint("/admin/ui/login", fallback);

  @Test
  @DisplayName("emits HX-Redirect header when request has HX-Request: true")
  void shouldEmitHxRedirect() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    req.addHeader("HX-Request", "true");
    MockHttpServletResponse resp = new MockHttpServletResponse();

    entryPoint.commence(req, resp, new InsufficientAuthenticationException("nope"));

    assertThat(resp.getStatus()).isEqualTo(200);
    assertThat(resp.getHeader("HX-Redirect")).isEqualTo("/admin/ui/login");
    Mockito.verifyNoInteractions(fallback);
  }

  @Test
  @DisplayName("delegates to fallback when no HX-Request header")
  void shouldDelegateWhenNotHtmx() throws Exception {
    HttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    HttpServletResponse resp = new MockHttpServletResponse();

    entryPoint.commence(req, resp, new InsufficientAuthenticationException("nope"));

    Mockito.verify(fallback).commence(Mockito.eq(req), Mockito.eq(resp), Mockito.any());
  }
}
