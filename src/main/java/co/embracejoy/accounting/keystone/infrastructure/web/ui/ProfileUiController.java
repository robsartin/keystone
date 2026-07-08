package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders the current caller's identity: their JWT/OIDC {@code sub}, the tenant resolved for this
 * request, and their role within that tenant. Read-only — any authenticated caller may view their
 * own profile, so this controller carries no {@code @PreAuthorize}.
 */
@Controller
public class ProfileUiController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  public ProfileUiController(UserRoleService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping("/admin/ui/profile")
  public String profile(Model model) {
    String sub = SecurityContextHolder.getContext().getAuthentication().getName();
    TenantId tenantId = tenantContext.current().orElse(null);
    model.addAttribute("sub", sub);
    model.addAttribute("tenantId", tenantId == null ? "(none)" : tenantId.value().toString());
    model.addAttribute("role", roleFor(tenantId, sub));
    return "profile";
  }

  private String roleFor(TenantId tenantId, String sub) {
    if (tenantId == null) {
      return "(no role)";
    }
    return service.findRole(tenantId, sub).map(r -> r.role().name()).orElse("(no role)");
  }
}
