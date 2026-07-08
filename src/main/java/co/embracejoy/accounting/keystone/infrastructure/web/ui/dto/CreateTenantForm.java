package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Form-bound payload for {@code POST /admin/ui/tenants}. */
public record CreateTenantForm(@NotBlank @Size(max = 200) String name) {}
