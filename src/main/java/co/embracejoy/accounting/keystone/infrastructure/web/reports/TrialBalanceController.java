package co.embracejoy.accounting.keystone.infrastructure.web.reports;

import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.infrastructure.web.reports.dto.TrialBalanceRowResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /reports/trial-balance} — read-only projection of postings, one row per {@code
 * (accountCode, currency)} pair.
 *
 * <p>Query params:
 *
 * <ul>
 *   <li>{@code asOf} — ISO date; defaults to today (UTC).
 *   <li>{@code includeZero} — boolean; defaults to {@code false} (suppress net-zero rows).
 * </ul>
 */
@RestController
@RequestMapping("/reports")
public class TrialBalanceController {

  private final TrialBalanceService service;

  public TrialBalanceController(TrialBalanceService service) {
    this.service = service;
  }

  @GetMapping("/trial-balance")
  @Operation(
      summary = "Trial balance",
      description =
          "Read-only projection of postings, one row per (accountCode, currency) pair with"
              + " debits, credits, balance in both transaction and base currency. asOf defaults to"
              + " today (UTC); includeZero defaults to false (suppress net-zero rows). Sorted by"
              + " (accountCode, currency).")
  public List<TrialBalanceRowResponse> get(
      @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf,
      @RequestParam(value = "includeZero", required = false, defaultValue = "false")
          boolean includeZero) {
    LocalDate effective = (asOf != null) ? asOf : LocalDate.now(ZoneOffset.UTC);
    return service.query(effective, includeZero).stream().map(TrialBalanceRowResponse::of).toList();
  }
}
