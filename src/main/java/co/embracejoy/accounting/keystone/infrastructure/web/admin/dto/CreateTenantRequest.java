package co.embracejoy.accounting.keystone.infrastructure.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(@NotBlank @Size(max = 200) String name) {

  @JsonCreator
  public CreateTenantRequest(@JsonProperty("name") String name) {
    this.name = name;
  }
}
