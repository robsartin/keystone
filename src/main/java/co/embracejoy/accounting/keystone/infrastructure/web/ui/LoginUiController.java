package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the admin UI's sign-in page, per Slice 5 Phase D-admin-ui T5. */
@Controller
public class LoginUiController {

  @GetMapping("/admin/ui/login")
  public String login() {
    return "login";
  }
}
