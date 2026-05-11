package co.embracejoy.accounting.keystone.infrastructure.web.dto;

/** Outbound representation of a single posting within a {@link JournalEntryResponse}. */
public record PostingResponse(String account, String side, long minorUnits) {}
