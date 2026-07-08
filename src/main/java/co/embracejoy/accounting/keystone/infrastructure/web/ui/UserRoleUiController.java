package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders the tenant's user-role list, per Slice 5 Phase D-admin-ui T6. Mutation handlers (grant /
 * revoke) land in T7.
 */
@Controller
@RequestMapping("/admin/ui/users")
public class UserRoleUiController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  public UserRoleUiController(UserRoleService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public String list(Model model) {
    model.addAttribute("users", service.findByTenant(tenantContext.require()));
    model.addAttribute("roles", List.of(Role.ADMIN, Role.BOOKKEEPER, Role.READ_ONLY));
    return "users";
  }
}
