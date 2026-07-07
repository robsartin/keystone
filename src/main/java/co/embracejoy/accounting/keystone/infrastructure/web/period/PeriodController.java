package co.embracejoy.accounting.keystone.infrastructure.web.period;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.period.dto.PeriodResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/periods")
@Validated
public class PeriodController {

  private static final String YEAR_MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";
  private static final String ACTOR_DEFAULT = "system";

  private final PeriodService service;
  private final TenantContext tenantContext;

  public PeriodController(PeriodService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "List periods",
      description =
          "Without query params, returns an empty list (open periods are implicit and not"
              + " enumerable). With ?status=closed, returns every closed period in chronological"
              + " order.")
  public List<PeriodResponse> list(
      @RequestParam(value = "status", required = false) String status) {
    if (status != null && status.equalsIgnoreCase("closed")) {
      TenantId tid = tenantContext.require();
      return service.findAllClosed(tid).stream().map(PeriodResponse::of).toList();
    }
    return List.of(); // OPEN periods are implicit; not enumerable
  }

  @GetMapping("/{yyyymm}")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "Fetch a period's status",
      description =
          "Returns the period's state for the given month (yyyy-MM). Months that have never been"
              + " closed return status OPEN with no recorded activity.")
  public ResponseEntity<?> get(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    TenantId tid = tenantContext.require();
    Period p = service.findByYearMonth(tid, YearMonth.parse(yyyymm));
    return ResponseEntity.ok(PeriodResponse.of(p));
  }

  @PostMapping("/{yyyymm}/close")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Close a period",
      description =
          "Closes the given month. Must close from the earliest open month with postings; closing"
              + " out of order is rejected. Closed periods reject new postings.")
  public ResponseEntity<?> close(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    TenantId tid = tenantContext.require();
    Result<Period, PeriodError> r = service.close(tid, YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  @PostMapping("/{yyyymm}/reopen")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Reopen the most recently closed period",
      description =
          "Reopens the given month. Only the most recently closed period can be reopened;"
              + " reopening earlier ones is rejected to preserve the sequential close invariant.")
  public ResponseEntity<?> reopen(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    TenantId tid = tenantContext.require();
    Result<Period, PeriodError> r = service.reopen(tid, YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  private ResponseEntity<ProblemDetail> error(PeriodError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
