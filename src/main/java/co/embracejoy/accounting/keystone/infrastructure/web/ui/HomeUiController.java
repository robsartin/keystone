package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Redirects the admin UI's root path to its default landing page, per Slice 5 Phase D-admin-ui T5.
 */
@Controller
@RequestMapping("/admin/ui")
public class HomeUiController {

  @GetMapping
  public String home() {
    return "redirect:/admin/ui/users";
  }
}
