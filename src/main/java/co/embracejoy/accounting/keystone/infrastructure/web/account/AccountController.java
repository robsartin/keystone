package co.embracejoy.accounting.keystone.infrastructure.web.account;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.AccountResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.CreateAccountRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.UpdateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

  private final AccountService service;
  private final TenantContext tenantContext;

  public AccountController(AccountService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
  @Operation(
      summary = "Create an account",
      description =
          "Creates a new account in the chart of accounts. The code must be unique. If parentCode"
              + " is supplied it must reference an existing account; the new account becomes a"
              + " leaf under that parent.")
  public ResponseEntity<?> create(@Valid @RequestBody CreateAccountRequest req) {
    Optional<AccountCode> parent = Optional.ofNullable(req.parentCode()).map(AccountCode::new);
    Result<Account, AccountError> r =
        service.create(
            tenantContext.require(),
            new AccountCode(req.code()),
            req.name(),
            AccountType.valueOf(req.type()),
            Currency.getInstance(req.currency()),
            parent);
    return r.fold(
        a ->
            ResponseEntity.created(URI.create("/accounts/" + a.code().value()))
                .body(AccountResponse.of(a)),
        this::error);
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "List all accounts",
      description = "Returns every account in the chart, ordered by code.")
  public List<AccountResponse> list() {
    return service.findAll().stream().map(AccountResponse::of).toList();
  }

  @GetMapping("/{code}")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "Fetch one account by code",
      description = "Returns the account with the given code, or 404 if no such account exists.")
  public ResponseEntity<?> get(@PathVariable String code) {
    return service
        .findByCode(new AccountCode(code))
        .<ResponseEntity<?>>map(a -> ResponseEntity.ok(AccountResponse.of(a)))
        .orElseGet(() -> error(new AccountError.NotFound(new AccountCode(code))));
  }

  @PatchMapping("/{code}")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
  @Operation(
      summary = "Rename or re-parent an account",
      description =
          "Applies optional rename (newCode) and/or re-parent (newParentCode). Pass an empty"
              + " newParentCode to detach the account from its current parent. Cycles are"
              + " rejected.")
  public ResponseEntity<?> update(
      @PathVariable String code, @Valid @RequestBody UpdateAccountRequest req) {
    AccountCode existing = new AccountCode(code);
    if (req.newCode() != null) {
      Result<Account, AccountError> r = service.rename(existing, new AccountCode(req.newCode()));
      if (r instanceof Result.Failure<Account, AccountError> f) {
        return error(f.error());
      }
      existing = new AccountCode(req.newCode()); // continue with new code for re-parent step
    }
    if (req.newParentCode() != null) {
      Optional<AccountCode> parent =
          req.newParentCode().isBlank()
              ? Optional.empty()
              : Optional.of(new AccountCode(req.newParentCode()));
      Result<Account, AccountError> r = service.setParent(existing, parent);
      if (r instanceof Result.Failure<Account, AccountError> f) {
        return error(f.error());
      }
    }
    AccountCode finalCode = existing;
    return service
        .findByCode(finalCode)
        .<ResponseEntity<?>>map(a -> ResponseEntity.ok(AccountResponse.of(a)))
        .orElseGet(() -> error(new AccountError.NotFound(finalCode)));
  }

  @PostMapping("/{code}/deactivate")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Deactivate an account",
      description =
          "Marks the account inactive. Inactive accounts reject new postings but historical"
              + " entries referencing them remain unchanged. Idempotent.")
  public ResponseEntity<?> deactivate(@PathVariable String code) {
    return service
        .deactivate(new AccountCode(code))
        .fold(a -> ResponseEntity.ok(AccountResponse.of(a)), this::error);
  }

  @PostMapping("/{code}/reactivate")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Reactivate an account",
      description = "Re-enables an account previously deactivated. Idempotent.")
  public ResponseEntity<?> reactivate(@PathVariable String code) {
    return service
        .reactivate(new AccountCode(code))
        .fold(a -> ResponseEntity.ok(AccountResponse.of(a)), this::error);
  }

  private ResponseEntity<ProblemDetail> error(AccountError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
