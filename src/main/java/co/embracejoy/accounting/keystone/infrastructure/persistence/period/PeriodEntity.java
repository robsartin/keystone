package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "periods")
class PeriodEntity {

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
      String yearMonth,
      String status,
      Instant closedAt,
      String closedBy,
      Instant reopenedAt,
      String reopenedBy) {
    this.yearMonth = yearMonth;
    this.status = status;
    this.closedAt = closedAt;
    this.closedBy = closedBy;
    this.reopenedAt = reopenedAt;
    this.reopenedBy = reopenedBy;
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
}
