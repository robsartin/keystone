package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Form-bound payload for {@code PUT /admin/ui/users/{userSub}}. */
public record ChangeRoleForm(
    @NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role) {}
