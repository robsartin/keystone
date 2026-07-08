package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AddUserForm;
import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.ChangeRoleForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders the tenant's user-role list (T6) and its add/change/revoke mutations (T7). Per ADR-0004,
 * domain failures are adjudicated inline here — no wrapper exceptions. Each mutation handler sets
 * the HTTP status on the response directly and returns the alert fragment view name on failure.
 */
@Controller
@RequestMapping("/admin/ui/users")
public class UserRoleUiController {

  private static final List<Role> ALL_ROLES = List.of(Role.ADMIN, Role.BOOKKEEPER, Role.READ_ONLY);

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
    model.addAttribute("roles", ALL_ROLES);
    return "users";
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public String add(
      @Valid @ModelAttribute AddUserForm form, Model model, HttpServletResponse resp) {
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), form.userSub(), Role.valueOf(form.role()), actor());
    return renderRowOrAlert(r, model, resp);
  }

  @PutMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public String change(
      @PathVariable String userSub,
      @Valid @ModelAttribute ChangeRoleForm form,
      Model model,
      HttpServletResponse resp) {
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), userSub, Role.valueOf(form.role()), actor());
    return renderRowOrAlert(r, model, resp);
  }

  @DeleteMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public String revoke(@PathVariable String userSub, Model model, HttpServletResponse resp) {
    Result<Void, SecurityError> r = service.revoke(tenantContext.require(), userSub, actor());
    if (r instanceof Result.Success<Void, SecurityError>) {
      return "fragments/empty :: empty";
    }
    SecurityError err = ((Result.Failure<Void, SecurityError>) r).error();
    resp.setStatus(UiResultMapper.statusFor(err).value());
    model.addAttribute("alert", UiResultMapper.toAlertView(err));
    return "fragments/alert :: alert";
  }

  private String renderRowOrAlert(
      Result<TenantUserRole, SecurityError> r, Model model, HttpServletResponse resp) {
    if (r instanceof Result.Success<TenantUserRole, SecurityError> ok) {
      model.addAttribute("user", ok.value());
      model.addAttribute("roles", ALL_ROLES);
      return "fragments/user-row :: row";
    }
    SecurityError err = ((Result.Failure<TenantUserRole, SecurityError>) r).error();
    resp.setStatus(UiResultMapper.statusFor(err).value());
    model.addAttribute("alert", UiResultMapper.toAlertView(err));
    return "fragments/alert :: alert";
  }

  private static String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
