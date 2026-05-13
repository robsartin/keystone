package co.embracejoy.accounting.keystone.domain.tenancy;

/**
 * Sealed errors produced by tenant operations. Mapped to ProblemDetail at the HTTP boundary by
 * {@code SecurityExceptionHandler} (Phase C).
 */
public sealed interface TenantError {

  /** No tenant exists with the given id. */
  record NotFound(TenantId id) implements TenantError {}

  /** A name was supplied but failed validation (e.g., blank). */
  record InvalidName(String reason) implements TenantError {}

  /** Attempted to operate on a deactivated tenant where active is required. */
  record Deactivated(TenantId id) implements TenantError {}
}
