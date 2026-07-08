package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

/** View model for a Bootstrap alert fragment rendered by {@code fragments/alert.html}. */
public record AlertView(String severity, String title, String detail) {}
