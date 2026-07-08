package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeUiController.class)
@DisplayName("HomeUiController")
class HomeUiControllerTest {

  @Autowired MockMvc mvc;

  @Test
  @WithMockUser
  @DisplayName("GET /admin/ui redirects to /admin/ui/users")
  void shouldRedirectRootToUsers() throws Exception {
    mvc.perform(get("/admin/ui"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "/admin/ui/users"));
  }
}
