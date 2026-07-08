package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Entry point for the {@code /admin/ui/**} filter chain that speaks HTMX's redirect protocol
 * instead of issuing a raw 302.
 *
 * <p>HTMX requests (identified by the {@code HX-Request: true} request header) expect a {@code 200}
 * response carrying an {@code HX-Redirect} header — HTMX then performs a full-page navigation to
 * that URL client-side. Returning a plain {@code 302 Location} for an HTMX (XHR) request would be
 * followed by the browser's fetch/XHR machinery, not the top-level document, leaving the user stuck
 * on the original page. Non-HTMX requests (a normal browser navigation) fall back to the delegate
 * entry point, typically one that redirects straight into the OAuth2 authorization endpoint.
 */
public class HtmxAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final String loginPath;
  private final AuthenticationEntryPoint fallback;

  public HtmxAuthenticationEntryPoint(String loginPath, AuthenticationEntryPoint fallback) {
    this.loginPath = loginPath;
    this.fallback = fallback;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setHeader("HX-Redirect", loginPath);
      return;
    }
    fallback.commence(request, response, exception);
  }
}
