package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(
    @Size(max = 64) String newCode, @Size(max = 64) String newParentCode) {}
