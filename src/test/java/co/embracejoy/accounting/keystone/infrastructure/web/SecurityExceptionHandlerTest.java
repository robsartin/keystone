package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

@DisplayName("SecurityExceptionHandler")
class SecurityExceptionHandlerTest {

  private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

  @Test
  @DisplayName("AuthenticationException maps to 401 with the unauthenticated type URI")
  void shouldReturn401ForGenericAuthenticationException() {
    ProblemDetail pd = handler.handle(new BadCredentialsException("bad credentials"));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(pd.getType().toString()).isEqualTo("/problems/auth/unauthenticated");
    assertThat(pd.getDetail()).isEqualTo("authentication required");
  }

  @Test
  @DisplayName("InvalidBearerTokenException(\"unknown tenant\") maps to 403 unknown-tenant")
  void shouldReturn403ForUnknownTenant() {
    ProblemDetail pd = handler.handle(new InvalidBearerTokenException("unknown tenant"));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(pd.getType().toString()).isEqualTo("/problems/auth/unknown-tenant");
  }

  @Test
  @DisplayName("AccessDeniedException maps to 403 with the insufficient-role type URI")
  void shouldReturn403ForAccessDenied() {
    ProblemDetail pd = handler.handle(new AccessDeniedException("denied"));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(pd.getType().toString()).isEqualTo("/problems/auth/insufficient-role");
    assertThat(pd.getDetail()).isEqualTo("authentication required");
  }
}
