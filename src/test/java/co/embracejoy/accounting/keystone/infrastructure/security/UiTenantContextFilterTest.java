package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@DisplayName("UiTenantContextFilter")
class UiTenantContextFilterTest {

  private final AuthenticationTenantResolver resolver = mock(AuthenticationTenantResolver.class);
  private final UiTenantContextFilter filter = new UiTenantContextFilter(resolver);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("resolves tenant when auth is an OAuth2AuthenticationToken with an OidcUser")
  void shouldResolveTenantForOidcUser() throws Exception {
    OidcIdToken idToken =
        OidcIdToken.withTokenValue("token")
            .claim("sub", "sas|admin")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken, "sub");
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keystone");
    SecurityContextHolder.getContext().setAuthentication(token);

    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verify(resolver).resolve(oidcUser);
    verify(chain).doFilter(req, resp);
  }

  @Test
  @DisplayName("does nothing when there is no authentication")
  void shouldDoNothingWhenAuthIsNull() throws Exception {
    SecurityContextHolder.clearContext();

    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verifyNoInteractions(resolver);
    verify(chain).doFilter(req, resp);
  }

  @Test
  @DisplayName("does nothing when auth is not an OAuth2AuthenticationToken (bearer-JWT chain)")
  void shouldDoNothingForNonOAuth2Authentication() throws Exception {
    var jwtLike = new TestingAuthenticationToken("sub", null, List.of());
    jwtLike.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(jwtLike);

    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(req, resp, chain);

    verifyNoInteractions(resolver);
    verify(chain).doFilter(req, resp);
  }
}
