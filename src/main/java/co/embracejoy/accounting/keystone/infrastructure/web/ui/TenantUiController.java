package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.application.tenancy.TenantService;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.CreateTenantForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders the platform-wide tenant list and its create/deactivate mutations. Per ADR-0004, domain
 * failures are adjudicated inline here — no wrapper exceptions. Each mutation handler sets the HTTP
 * status on the response directly and returns the alert fragment view name on failure.
 */
@Controller
@RequestMapping("/admin/ui/tenants")
public class TenantUiController {

  private final TenantService service;

  public TenantUiController(TenantService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public String list(Model model) {
    model.addAttribute("tenants", service.findAll());
    return "tenants";
  }

  @PostMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public String create(
      @Valid @ModelAttribute CreateTenantForm form, Model model, HttpServletResponse resp) {
    Result<Tenant, TenantError> r = service.create(form.name());
    return renderRowOrAlert(r, model, resp);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public String detail(@PathVariable String id, Model model, HttpServletResponse resp) {
    Optional<TenantId> tenantId = parseId(id);
    if (tenantId.isEmpty()) {
      return alertForRawId(id, model, resp);
    }
    Optional<Tenant> tenant = service.findById(tenantId.get());
    if (tenant.isEmpty()) {
      return alert(new TenantError.NotFound(tenantId.get()), model, resp);
    }
    model.addAttribute("tenant", tenant.get());
    return "tenant-detail";
  }

  @PostMapping("/{id}/deactivate")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public String deactivate(@PathVariable String id, Model model, HttpServletResponse resp) {
    Optional<TenantId> tenantId = parseId(id);
    if (tenantId.isEmpty()) {
      return alertForRawId(id, model, resp);
    }
    Result<Tenant, TenantError> r = service.deactivate(tenantId.get());
    return renderRowOrAlert(r, model, resp);
  }

  private String renderRowOrAlert(
      Result<Tenant, TenantError> r, Model model, HttpServletResponse resp) {
    if (r instanceof Result.Success<Tenant, TenantError> ok) {
      model.addAttribute("tenant", ok.value());
      return "fragments/tenant-row :: row";
    }
    TenantError err = ((Result.Failure<Tenant, TenantError>) r).error();
    return alert(err, model, resp);
  }

  private String alert(TenantError err, Model model, HttpServletResponse resp) {
    resp.setStatus(UiResultMapper.statusFor(err).value());
    model.addAttribute("alert", UiResultMapper.toAlertView(err));
    return "fragments/alert :: alert";
  }

  private String alertForRawId(String rawId, Model model, HttpServletResponse resp) {
    ProblemDetail pd = ResultMapper.tenantNotFoundByRawId(rawId);
    resp.setStatus(pd.getStatus());
    model.addAttribute("alert", UiResultMapper.toAlertView(pd));
    return "fragments/alert :: alert";
  }

  private static Optional<TenantId> parseId(String id) {
    try {
      return Optional.of(new TenantId(UUID.fromString(id)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
