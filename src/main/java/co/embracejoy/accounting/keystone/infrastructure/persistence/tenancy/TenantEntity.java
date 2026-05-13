package co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
class TenantEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "deactivated_at")
  private Instant deactivatedAt;

  protected TenantEntity() {
    // JPA required no-arg constructor
  }

  TenantEntity(UUID id, String name, Instant createdAt, Instant deactivatedAt) {
    this.id = id;
    this.name = name;
    this.createdAt = createdAt;
    this.deactivatedAt = deactivatedAt;
  }

  UUID getId() {
    return id;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getDeactivatedAt() {
    return deactivatedAt;
  }

  void setDeactivatedAt(Instant deactivatedAt) {
    this.deactivatedAt = deactivatedAt;
  }
}
