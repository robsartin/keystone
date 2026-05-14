package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

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
@Table(name = "periods")
@IdClass(PeriodEntity.Key.class)
class PeriodEntity {

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Id
  @Column(name = "year_month", nullable = false, length = 7, updatable = false)
  private String yearMonth;

  @Column(name = "status", nullable = false, length = 8)
  private String status;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "closed_by", length = 200)
  private String closedBy;

  @Column(name = "reopened_at")
  private Instant reopenedAt;

  @Column(name = "reopened_by", length = 200)
  private String reopenedBy;

  protected PeriodEntity() {
    // JPA required no-arg constructor
  }

  PeriodEntity(
      UUID tenantId,
      String yearMonth,
      String status,
      Instant closedAt,
      String closedBy,
      Instant reopenedAt,
      String reopenedBy) {
    this.tenantId = tenantId;
    this.yearMonth = yearMonth;
    this.status = status;
    this.closedAt = closedAt;
    this.closedBy = closedBy;
    this.reopenedAt = reopenedAt;
    this.reopenedBy = reopenedBy;
  }

  UUID getTenantId() {
    return tenantId;
  }

  String getYearMonth() {
    return yearMonth;
  }

  String getStatus() {
    return status;
  }

  Instant getClosedAt() {
    return closedAt;
  }

  String getClosedBy() {
    return closedBy;
  }

  Instant getReopenedAt() {
    return reopenedAt;
  }

  String getReopenedBy() {
    return reopenedBy;
  }

  void setStatus(String status) {
    this.status = status;
  }

  void setClosedAt(Instant closedAt) {
    this.closedAt = closedAt;
  }

  void setClosedBy(String closedBy) {
    this.closedBy = closedBy;
  }

  void setReopenedAt(Instant reopenedAt) {
    this.reopenedAt = reopenedAt;
  }

  void setReopenedBy(String reopenedBy) {
    this.reopenedBy = reopenedBy;
  }

  /** Composite primary key for {@link PeriodEntity}. */
  static class Key implements Serializable {
    private UUID tenantId;
    private String yearMonth;

    public Key() {
      // JPA required no-arg constructor
    }

    public Key(UUID tenantId, String yearMonth) {
      this.tenantId = tenantId;
      this.yearMonth = yearMonth;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key k)) {
        return false;
      }
      return Objects.equals(tenantId, k.tenantId) && Objects.equals(yearMonth, k.yearMonth);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, yearMonth);
    }
  }
}
