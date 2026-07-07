package co.embracejoy.accounting.keystone.infrastructure.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record GrantRoleRequest(
    @NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role) {

  @JsonCreator
  public GrantRoleRequest(@JsonProperty("role") String role) {
    this.role = role;
  }
}
