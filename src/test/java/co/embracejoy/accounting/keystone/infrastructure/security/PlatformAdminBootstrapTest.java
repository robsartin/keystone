package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdmin;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PlatformAdminBootstrap")
class PlatformAdminBootstrapTest {

  @Test
  @DisplayName("no-op when bootstrap-sub is null")
  void shouldSkipWhenSubNull() throws Exception {
    PlatformAdminRepository repo = mock(PlatformAdminRepository.class);
    KeystoneSecurityProperties props = new KeystoneSecurityProperties(null, null, null, null);
    new PlatformAdminBootstrap(props, repo).run(null);
    verify(repo, never()).grant(anyString());
  }

  @Test
  @DisplayName("no-op when bootstrap-sub is blank")
  void shouldSkipWhenSubBlank() throws Exception {
    PlatformAdminRepository repo = mock(PlatformAdminRepository.class);
    KeystoneSecurityProperties props = new KeystoneSecurityProperties(null, null, null, "   ");
    new PlatformAdminBootstrap(props, repo).run(null);
    verify(repo, never()).grant(anyString());
  }

  @Test
  @DisplayName("calls grant(sub) once when configured")
  void shouldGrantWhenConfigured() throws Exception {
    PlatformAdminRepository repo = mock(PlatformAdminRepository.class);
    Mockito.when(repo.grant("auth0|root"))
        .thenReturn(new PlatformAdmin("auth0|root", Instant.now()));
    KeystoneSecurityProperties props =
        new KeystoneSecurityProperties(null, null, null, "auth0|root");

    new PlatformAdminBootstrap(props, repo).run(null);

    verify(repo, times(1)).grant(eq("auth0|root"));
  }
}
