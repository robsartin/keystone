package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "platform_admins")
class PlatformAdminEntity {

  @Id
  @Column(name = "user_sub", nullable = false, updatable = false, length = 255)
  private String userSub;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private Instant grantedAt;

  protected PlatformAdminEntity() {
    // JPA required no-arg constructor
  }

  PlatformAdminEntity(String userSub, Instant grantedAt) {
    this.userSub = userSub;
    this.grantedAt = grantedAt;
  }

  String getUserSub() {
    return userSub;
  }

  Instant getGrantedAt() {
    return grantedAt;
  }
}
