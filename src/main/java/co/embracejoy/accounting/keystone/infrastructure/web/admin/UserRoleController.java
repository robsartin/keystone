package co.embracejoy.accounting.keystone.infrastructure.web.admin;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.admin.dto.GrantRoleRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.admin.dto.UserRoleResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
public class UserRoleController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  public UserRoleController(UserRoleService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public List<UserRoleResponse> list() {
    return service.findByTenant(tenantContext.require()).stream()
        .map(UserRoleResponse::of)
        .toList();
  }

  @GetMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> get(@PathVariable String userSub) {
    Optional<TenantUserRole> found = service.findRole(tenantContext.require(), userSub);
    return found
        .<ResponseEntity<?>>map(r -> ResponseEntity.ok(UserRoleResponse.of(r)))
        .orElseGet(() -> error(new SecurityError.RoleNotFound(tenantContext.require(), userSub)));
  }

  @PutMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> grant(
      @PathVariable String userSub, @Valid @RequestBody GrantRoleRequest req) {
    String currentUserSub = currentUserSub();
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), userSub, Role.valueOf(req.role()), currentUserSub);
    return r.fold(row -> ResponseEntity.ok(UserRoleResponse.of(row)), this::error);
  }

  @DeleteMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> revoke(@PathVariable String userSub) {
    String currentUserSub = currentUserSub();
    Result<Void, SecurityError> r =
        service.revoke(tenantContext.require(), userSub, currentUserSub);
    return r.fold(v -> ResponseEntity.noContent().build(), this::error);
  }

  private static String currentUserSub() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  private ResponseEntity<ProblemDetail> error(SecurityError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
