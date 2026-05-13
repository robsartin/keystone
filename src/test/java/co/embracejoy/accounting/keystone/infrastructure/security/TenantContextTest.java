package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantContext")
class TenantContextTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));

  @Test
  @DisplayName("require() throws when no tenant has been set")
  void shouldThrowWhenContextEmpty() {
    TenantContext ctx = new TenantContext();
    assertThrows(IllegalStateException.class, ctx::require);
  }

  @Test
  @DisplayName("set() then require() returns the value")
  void shouldReturnValueAfterSet() {
    TenantContext ctx = new TenantContext();
    ctx.set(TENANT);
    assertThat(ctx.require()).isEqualTo(TENANT);
  }

  @Test
  @DisplayName("current() returns Optional.empty() when unset")
  void shouldReturnEmptyOptionalWhenUnset() {
    TenantContext ctx = new TenantContext();
    assertThat(ctx.current()).isEmpty();
  }

  @Test
  @DisplayName("current() returns the value when set")
  void shouldReturnOptionalOfValueWhenSet() {
    TenantContext ctx = new TenantContext();
    ctx.set(TENANT);
    assertThat(ctx.current()).contains(TENANT);
  }

  @Test
  @DisplayName("set() rejects null")
  void shouldRejectNullSet() {
    TenantContext ctx = new TenantContext();
    assertThrows(NullPointerException.class, () -> ctx.set(null));
  }

  @Test
  @DisplayName("set() overwrites a previous value (idempotent overwrite)")
  void shouldOverwritePreviousValue() {
    TenantContext ctx = new TenantContext();
    TenantId other = new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000002"));
    ctx.set(TENANT);
    ctx.set(other);
    assertThat(ctx.require()).isEqualTo(other);
  }
}
