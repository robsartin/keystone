package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Pattern(regexp = "^(ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE)$") String type,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @Size(max = 64) String parentCode) {

  @JsonCreator
  public CreateAccountRequest(
      @JsonProperty("code") String code,
      @JsonProperty("name") String name,
      @JsonProperty("type") String type,
      @JsonProperty("currency") String currency,
      @JsonProperty("parentCode") String parentCode) {
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
  }
}
