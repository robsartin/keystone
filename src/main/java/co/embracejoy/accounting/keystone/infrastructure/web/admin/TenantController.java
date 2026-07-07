package co.embracejoy.accounting.keystone.infrastructure.web.admin;

import co.embracejoy.accounting.keystone.application.tenancy.TenantService;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.admin.dto.CreateTenantRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.admin.dto.TenantResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

  private final TenantService service;

  public TenantController(TenantService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public ResponseEntity<?> create(@Valid @RequestBody CreateTenantRequest req) {
    Result<Tenant, TenantError> r = service.create(req.name());
    return r.fold(
        t ->
            ResponseEntity.created(URI.create("/admin/tenants/" + t.id().value()))
                .body(TenantResponse.of(t)),
        this::error);
  }

  @GetMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public List<TenantResponse> list() {
    return service.findAll().stream().map(TenantResponse::of).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public ResponseEntity<?> get(@PathVariable String id) {
    TenantId tenantId;
    try {
      tenantId = new TenantId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return notFoundByRawId(id);
    }
    return service
        .findById(tenantId)
        .<ResponseEntity<?>>map(t -> ResponseEntity.ok(TenantResponse.of(t)))
        .orElseGet(() -> error(new TenantError.NotFound(tenantId)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  public ResponseEntity<?> delete(@PathVariable String id) {
    TenantId tenantId;
    try {
      tenantId = new TenantId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return notFoundByRawId(id);
    }
    Result<Tenant, TenantError> r = service.deactivate(tenantId);
    return r.fold(
        t -> ResponseEntity.ok(TenantResponse.of(t)), err -> handleDeleteError(tenantId, err));
  }

  private ResponseEntity<?> handleDeleteError(TenantId tenantId, TenantError err) {
    if (err instanceof TenantError.Deactivated) {
      return service
          .findById(tenantId)
          .<ResponseEntity<?>>map(t -> ResponseEntity.ok(TenantResponse.of(t)))
          .orElseGet(() -> error(new TenantError.NotFound(tenantId)));
    }
    return error(err);
  }

  private ResponseEntity<ProblemDetail> error(TenantError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }

  private ResponseEntity<ProblemDetail> notFoundByRawId(String rawId) {
    ProblemDetail pd = ResultMapper.tenantNotFoundByRawId(rawId);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
