package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "accounts")
class AccountEntity {

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
      String code, String name, String type, String currency, String parentCode, boolean active) {
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
    this.active = active;
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
}
