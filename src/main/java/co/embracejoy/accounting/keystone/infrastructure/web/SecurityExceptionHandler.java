package co.embracejoy.accounting.keystone.infrastructure.web;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Spring Security exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Deliberately separate from {@link ValidationExceptionHandler} — auth failures are a distinct
 * concern from request validation. Messages are generic and never reflect JWT contents: leaking
 * claims or validator internals back to the caller would be an information disclosure.
 *
 * <p>{@link org.springframework.security.oauth2.server.resource.InvalidBearerTokenException}
 * (thrown by {@code JwtTenantConverter} for an unresolvable tenant claim) is an {@link
 * AuthenticationException}. Per ADR-0017, an unknown tenant is treated as 403 (the token is
 * otherwise valid, but the caller has no access to any tenant) rather than 401; we detect that case
 * by its message since a dedicated exception type would be the cleaner long-term fix.
 */
@RestControllerAdvice(annotations = RestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

  private static final String PROBLEM_BASE = "/problems/auth";

  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail handle(AuthenticationException ex) {
    if ("unknown tenant".equals(ex.getMessage())) {
      return problem(HttpStatus.FORBIDDEN, "unknown-tenant", "authentication required");
    }
    return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "authentication required");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail handle(AccessDeniedException ex) {
    return problem(HttpStatus.FORBIDDEN, "insufficient-role", "authentication required");
  }

  private static ProblemDetail problem(HttpStatus status, String typeSuffix, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(PROBLEM_BASE + "/" + typeSuffix));
    pd.setTitle(status.getReasonPhrase());
    return pd;
  }
}
