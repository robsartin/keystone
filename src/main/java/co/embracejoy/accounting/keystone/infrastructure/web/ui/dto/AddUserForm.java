package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Form-bound payload for {@code POST /admin/ui/users}. */
public record AddUserForm(
    @NotBlank @Size(max = 200) String userSub,
    @NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role) {}
