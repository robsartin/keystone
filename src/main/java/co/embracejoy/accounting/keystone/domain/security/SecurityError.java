package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;

/**
 * Sealed errors produced by security operations. Mapped to RFC 9457 ProblemDetail at the HTTP
 * boundary by {@code SecurityExceptionHandler} (Phase C). The HTTP status for each variant is
 * documented inline.
 */
public sealed interface SecurityError {

  /** A user role assignment was looked up but not found. → 404. */
  record RoleNotFound(TenantId tenantId, String userSub) implements SecurityError {}

  /**
   * The lone tenant Admin attempted to demote themselves. The tenant would have zero admins. → 400
   * {@code /problems/admin/cannot-orphan-self}.
   */
  record CannotOrphanSelf(TenantId tenantId, String userSub) implements SecurityError {}

  /** The current request has no usable tenant in context. → 403. */
  record MissingTenant() implements SecurityError {}

  /** The JWT carried a tenant claim referencing a non-existent tenant. → 403. */
  record UnknownTenant(TenantId tenantId) implements SecurityError {}

  /** The current user lacks the required role/permission for the endpoint. → 403. */
  record InsufficientRole(String required) implements SecurityError {}
}
