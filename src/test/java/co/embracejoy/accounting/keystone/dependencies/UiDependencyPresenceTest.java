package co.embracejoy.accounting.keystone.dependencies;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UI dependencies")
class UiDependencyPresenceTest {

  @Test
  @DisplayName("thymeleaf ClassLoader.loadClass resolves TemplateEngine")
  void shouldResolveThymeleafTemplateEngine() {
    assertThatCode(() -> Class.forName("org.thymeleaf.TemplateEngine")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("spring-security-oauth2-client resolves OAuth2LoginConfigurer")
  void shouldResolveOAuth2LoginConfigurer() {
    String className =
        "org.springframework.security.config.annotation.web.configurers.oauth2.client"
            + ".OAuth2LoginConfigurer";
    assertThatCode(() -> Class.forName(className)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("spring-security-oauth2-authorization-server resolves AuthorizationServerSettings")
  void shouldResolveAuthorizationServerSettings() {
    String className =
        "org.springframework.security.oauth2.server.authorization.settings"
            + ".AuthorizationServerSettings";
    assertThatCode(() -> Class.forName(className)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("playwright resolves Playwright entry point")
  void shouldResolvePlaywright() {
    assertThatCode(() -> Class.forName("com.microsoft.playwright.Playwright"))
        .doesNotThrowAnyException();
  }
}
