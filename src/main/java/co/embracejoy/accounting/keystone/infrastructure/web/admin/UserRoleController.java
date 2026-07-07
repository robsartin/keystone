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
import io.swagger.v3.oas.annotations.Operation;
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
  @Operation(
      summary = "List user role assignments in the current tenant",
      description =
          "Returns every user role assignment scoped to the caller's tenant, ordered by grantedAt"
              + " ascending.")
  public List<UserRoleResponse> list() {
    return service.findByTenant(tenantContext.require()).stream()
        .map(UserRoleResponse::of)
        .toList();
  }

  @GetMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Fetch one user's role in the current tenant",
      description =
          "Returns the given user's role assignment in the caller's tenant, or 404 if the user has"
              + " no role granted here.")
  public ResponseEntity<?> get(@PathVariable String userSub) {
    Optional<TenantUserRole> found = service.findRole(tenantContext.require(), userSub);
    return found
        .<ResponseEntity<?>>map(r -> ResponseEntity.ok(UserRoleResponse.of(r)))
        .orElseGet(() -> error(new SecurityError.RoleNotFound(tenantContext.require(), userSub)));
  }

  @PutMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Grant or change a user's role",
      description =
          "Assigns the given role (ADMIN, BOOKKEEPER, or READ_ONLY) to the user in the caller's"
              + " tenant. Idempotent: re-granting the same role is a no-op. Demoting the lone"
              + " tenant Admin (yourself) is rejected with 400.")
  public ResponseEntity<?> grant(
      @PathVariable String userSub, @Valid @RequestBody GrantRoleRequest req) {
    String currentUserSub = currentUserSub();
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), userSub, Role.valueOf(req.role()), currentUserSub);
    return r.fold(row -> ResponseEntity.ok(UserRoleResponse.of(row)), this::error);
  }

  @DeleteMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Revoke a user's role",
      description =
          "Removes the user's role assignment in the caller's tenant. Returns 204 on success, 404"
              + " if no role was granted, or 400 if the caller is the lone tenant Admin.")
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
