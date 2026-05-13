package co.embracejoy.accounting.keystone.infrastructure.web.period;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.period.dto.PeriodResponse;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

  public PeriodController(PeriodService service) {
    this.service = service;
  }

  @GetMapping
  public List<PeriodResponse> list(
      @RequestParam(value = "status", required = false) String status) {
    if (status != null && status.equalsIgnoreCase("closed")) {
      return service.findAllClosed().stream().map(PeriodResponse::of).toList();
    }
    return List.of(); // OPEN periods are implicit; not enumerable
  }

  @GetMapping("/{yyyymm}")
  public ResponseEntity<?> get(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Period p = service.findByYearMonth(YearMonth.parse(yyyymm));
    return ResponseEntity.ok(PeriodResponse.of(p));
  }

  @PostMapping("/{yyyymm}/close")
  public ResponseEntity<?> close(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Result<Period, PeriodError> r = service.close(YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  @PostMapping("/{yyyymm}/reopen")
  public ResponseEntity<?> reopen(
      @PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Result<Period, PeriodError> r = service.reopen(YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  private ResponseEntity<ProblemDetail> error(PeriodError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
