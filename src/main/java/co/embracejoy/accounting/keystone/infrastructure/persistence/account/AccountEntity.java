package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@IdClass(AccountEntity.Key.class)
class AccountEntity {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Id
  @Column(name = "code", nullable = false, length = 64, updatable = false)
  private String code;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "type", nullable = false, length = 16)
  private String type;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "parent_code", length = 64)
  private String parentCode;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false)
  private Instant updatedAt;

  protected AccountEntity() {
    // JPA required no-arg constructor
  }

  AccountEntity(
      UUID tenantId,
      String code,
      String name,
      String type,
      String currency,
      String parentCode,
      boolean active) {
    this.tenantId = tenantId;
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
    this.active = active;
  }

  UUID getTenantId() {
    return tenantId;
  }

  String getCode() {
    return code;
  }

  String getName() {
    return name;
  }

  String getType() {
    return type;
  }

  String getCurrency() {
    return currency;
  }

  String getParentCode() {
    return parentCode;
  }

  boolean isActive() {
    return active;
  }

  void setName(String name) {
    this.name = name;
  }

  void setParentCode(String parentCode) {
    this.parentCode = parentCode;
  }

  void setActive(boolean active) {
    this.active = active;
  }

  /** Composite primary key for {@link AccountEntity}. */
  static class Key implements Serializable {
    private UUID tenantId;
    private String code;

    public Key() {
      // JPA required no-arg constructor
    }

    public Key(UUID tenantId, String code) {
      this.tenantId = tenantId;
      this.code = code;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key k)) {
        return false;
      }
      return Objects.equals(tenantId, k.tenantId) && Objects.equals(code, k.code);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, code);
    }
  }
}
