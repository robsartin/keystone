package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

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
@Table(name = "tenant_user_roles")
@IdClass(TenantUserRoleEntity.Key.class)
class TenantUserRoleEntity {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Id
  @Column(name = "user_sub", nullable = false, updatable = false, length = 255)
  private String userSub;

  @Column(name = "role", nullable = false, length = 32)
  private String role;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private Instant grantedAt;

  @Column(name = "granted_by", nullable = false, length = 255)
  private String grantedBy;

  protected TenantUserRoleEntity() {
    // JPA required no-arg constructor
  }

  TenantUserRoleEntity(
      UUID tenantId, String userSub, String role, Instant grantedAt, String grantedBy) {
    this.tenantId = tenantId;
    this.userSub = userSub;
    this.role = role;
    this.grantedAt = grantedAt;
    this.grantedBy = grantedBy;
  }

  UUID getTenantId() {
    return tenantId;
  }

  String getUserSub() {
    return userSub;
  }

  String getRole() {
    return role;
  }

  void setRole(String role) {
    this.role = role;
  }

  Instant getGrantedAt() {
    return grantedAt;
  }

  String getGrantedBy() {
    return grantedBy;
  }

  void setGrantedBy(String grantedBy) {
    this.grantedBy = grantedBy;
  }

  /** Composite key for {@link TenantUserRoleEntity}. */
  static class Key implements Serializable {
    private UUID tenantId;
    private String userSub;

    public Key() {
      // JPA required no-arg constructor
    }

    public Key(UUID tenantId, String userSub) {
      this.tenantId = tenantId;
      this.userSub = userSub;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key k)) {
        return false;
      }
      return Objects.equals(tenantId, k.tenantId) && Objects.equals(userSub, k.userSub);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, userSub);
    }
  }
}
