package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code UiSecurityConfig} already permits anonymous access to {@code /admin/ui/login} (see {@code
 * UiSecurityConfig.uiFilterChain}'s {@code requestMatchers("/admin/ui/login").permitAll()} ), but
 * that chain also wires {@code oauth2Login()} against a real {@code ClientRegistrationRepository}
 * and {@code AuthenticationTenantResolver} against real JPA repositories — none of which belong in
 * a {@code @WebMvcTest} slice for this thin controller. This test renders the login page via a
 * MockMvc slice with the security filter chain disabled ({@code addFilters = false}); it verifies
 * view rendering only. Anonymous access is verified end-to-end by {@code
 * OAuth2LoginFlowIT#shouldAllowAnonymousLoginPageAccess}.
 */
@WebMvcTest(LoginUiController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("LoginUiController")
class LoginUiControllerTest {

  @Autowired MockMvc mvc;

  @Test
  @DisplayName("GET /admin/ui/login renders sign-in page permitting anon")
  void shouldRenderLoginPage() throws Exception {
    mvc.perform(get("/admin/ui/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sign in")))
        .andExpect(content().string(containsString("/oauth2/authorization/keystone")));
  }
}
