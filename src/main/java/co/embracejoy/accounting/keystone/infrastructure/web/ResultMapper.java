package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Translates domain error types into RFC 9457 {@link ProblemDetail} responses. */
public final class ResultMapper {

  private static final String PROBLEM_BASE = "https://embracejoy.co/problems";

  private ResultMapper() {
    // static utility class; no instances
  }

  public static ProblemDetail toProblemDetail(JournalError error) {
    return switch (error) {
      case JournalError.NoPostings ignored -> noPostings();
      case JournalError.MixedCurrencies mc -> mixedCurrencies(mc);
      case JournalError.Unbalanced u -> unbalanced(u);
      case JournalError.Overflow o -> overflow(o);
      case JournalError.AccountNotFound a -> journalAccountNotFound(a);
      case JournalError.AccountInactive a -> journalAccountInactive(a);
      case JournalError.AccountNotALeaf a -> journalAccountNotALeaf(a);
      case JournalError.AccountCurrencyMismatch a -> journalAccountCurrencyMismatch(a);
      case JournalError.PostingInClosedPeriod p -> journalPostingInClosedPeriod(p);
      case JournalError.BaseCurrencyMismatch m ->
          problem(
              HttpStatus.BAD_REQUEST,
              "/journal/base-currency-mismatch",
              "Posting baseAmount currency does not match the configured base",
              "Account '"
                  + m.code().value()
                  + "' posting carries baseAmount in "
                  + m.actualOnPosting().getCurrencyCode()
                  + " but the configured base currency is "
                  + m.expectedByConfig().getCurrencyCode()
                  + ".");
    };
  }

  public static ProblemDetail toProblemDetail(PeriodError err) {
    return switch (err) {
      case PeriodError.NotSequentiallyClosable n ->
          problem(
              HttpStatus.BAD_REQUEST,
              "/period/not-sequentially-closable",
              "Period close is out of order",
              "Cannot close "
                  + n.attempted()
                  + "; close "
                  + n.earliestOpenActive()
                  + " (or an earlier month) first.");
      case PeriodError.NotMostRecentlyClosed n ->
          problem(
              HttpStatus.BAD_REQUEST,
              "/period/not-most-recently-closed",
              "Period reopen requires the most-recently-closed period",
              "Cannot reopen "
                  + n.attempted()
                  + n.latestClosed()
                      .map(lc -> "; latest closed is " + lc)
                      .orElse("; no closed periods"));
      case PeriodError.NotFound nf ->
          problem(
              HttpStatus.NOT_FOUND,
              "/period/not-found",
              "Period not found",
              "No period row for " + nf.yearMonth() + ".");
    };
  }

  public static ProblemDetail toProblemDetail(TenantError err) {
    return switch (err) {
      case TenantError.NotFound n ->
          problem(
              HttpStatus.NOT_FOUND,
              "/admin/tenant-not-found",
              "Tenant not found",
              "No tenant with id '" + n.id().value() + "'.");
      case TenantError.InvalidName in ->
          problem(
              HttpStatus.BAD_REQUEST,
              "/admin/tenant-invalid-name",
              "Tenant name is invalid",
              in.reason());
      case TenantError.Deactivated d ->
          problem(
              HttpStatus.BAD_REQUEST,
              "/admin/tenant-deactivated",
              "Tenant is deactivated",
              "Tenant '" + d.id().value() + "' is deactivated.");
    };
  }

  public static ProblemDetail toProblemDetail(SecurityError err) {
    return switch (err) {
      case SecurityError.RoleNotFound r -> securityRoleNotFound(r);
      case SecurityError.CannotOrphanSelf c -> securityCannotOrphanSelf(c);
      case SecurityError.MissingTenant m -> securityMissingTenant();
      case SecurityError.UnknownTenant u -> securityUnknownTenant(u);
      case SecurityError.InsufficientRole i -> securityInsufficientRole(i);
    };
  }

  private static ProblemDetail securityRoleNotFound(SecurityError.RoleNotFound r) {
    return problem(
        HttpStatus.NOT_FOUND,
        "/admin/user-role-not-found",
        "User role not found",
        "No role assignment for user '"
            + r.userSub()
            + "' in tenant '"
            + r.tenantId().value()
            + "'.");
  }

  private static ProblemDetail securityCannotOrphanSelf(SecurityError.CannotOrphanSelf c) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/admin/cannot-orphan-self",
        "Cannot orphan the lone tenant admin",
        "User '"
            + c.userSub()
            + "' is the only Admin in tenant '"
            + c.tenantId().value()
            + "' and cannot demote or remove themselves.");
  }

  private static ProblemDetail securityMissingTenant() {
    return problem(
        HttpStatus.FORBIDDEN,
        "/auth/missing-tenant",
        "No tenant in context",
        "Request has no resolvable tenant context.");
  }

  private static ProblemDetail securityUnknownTenant(SecurityError.UnknownTenant u) {
    return problem(
        HttpStatus.FORBIDDEN,
        "/auth/unknown-tenant",
        "Unknown tenant",
        "Tenant '" + u.tenantId().value() + "' is not recognized.");
  }

  private static ProblemDetail securityInsufficientRole(SecurityError.InsufficientRole i) {
    return problem(
        HttpStatus.FORBIDDEN,
        "/auth/insufficient-role",
        "Insufficient role",
        "This endpoint requires role: " + i.required() + ".");
  }

  public static ProblemDetail tenantNotFoundByRawId(String rawId) {
    return problem(
        HttpStatus.NOT_FOUND,
        "/admin/tenant-not-found",
        "Tenant not found",
        "No tenant with id '" + rawId + "'.");
  }

  public static ProblemDetail toProblemDetail(AccountError err) {
    return switch (err) {
      case AccountError.CodeAlreadyExists c -> accountCodeAlreadyExists(c);
      case AccountError.NotFound n -> accountNotFound(n);
      case AccountError.ParentNotFound p -> accountParentNotFound(p);
      case AccountError.CycleWouldBeCreated c -> accountCycleWouldBeCreated(c);
      case AccountError.CodeInUseByPosting u -> accountCodeInUseByPosting(u);
    };
  }

  private static ProblemDetail accountCodeAlreadyExists(AccountError.CodeAlreadyExists c) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/account/code-already-exists",
        "Account code already exists",
        "An account with code '" + c.code().value() + "' already exists.");
  }

  private static ProblemDetail accountNotFound(AccountError.NotFound n) {
    return problem(
        HttpStatus.NOT_FOUND,
        "/account/not-found",
        "Account not found",
        "No account with code '" + n.code().value() + "'.");
  }

  private static ProblemDetail accountParentNotFound(AccountError.ParentNotFound p) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/account/parent-not-found",
        "Parent account not found",
        "No account with code '" + p.parentCode().value() + "' to set as parent.");
  }

  private static ProblemDetail accountCycleWouldBeCreated(AccountError.CycleWouldBeCreated c) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/account/cycle-would-be-created",
        "Account hierarchy would form a cycle",
        "Setting '"
            + c.child().value()
            + "' parent to '"
            + c.proposedParent().value()
            + "' would create a cycle.");
  }

  private static ProblemDetail accountCodeInUseByPosting(AccountError.CodeInUseByPosting u) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/account/code-in-use-by-posting",
        "Account code already in use",
        "Code '" + u.code().value() + "' is already in use; pick a different code.");
  }

  private static ProblemDetail journalAccountNotFound(JournalError.AccountNotFound a) {
    return problem(
        "/journal/account-not-found",
        "Posting references an unknown account",
        "Account code '" + a.code().value() + "' does not exist.");
  }

  private static ProblemDetail journalAccountInactive(JournalError.AccountInactive a) {
    return problem(
        "/journal/account-inactive",
        "Posting references a deactivated account",
        "Account '" + a.code().value() + "' is not active.");
  }

  private static ProblemDetail journalAccountNotALeaf(JournalError.AccountNotALeaf a) {
    return problem(
        "/journal/account-not-a-leaf",
        "Posting targets a non-leaf account",
        "Account '" + a.code().value() + "' has children; post to a leaf instead.");
  }

  private static ProblemDetail journalAccountCurrencyMismatch(
      JournalError.AccountCurrencyMismatch a) {
    return problem(
        "/journal/account-currency-mismatch",
        "Posting currency does not match account currency",
        "Account '"
            + a.code().value()
            + "' uses "
            + a.expectedByAccount().getCurrencyCode()
            + " but the posting amount uses "
            + a.actualOnPosting().getCurrencyCode()
            + ".");
  }

  private static ProblemDetail noPostings() {
    return problem(
        "/journal/no-postings",
        "Journal entry has no postings",
        "A journal entry must contain at least one posting.");
  }

  private static ProblemDetail mixedCurrencies(JournalError.MixedCurrencies mc) {
    String codes =
        mc.currencies().stream().map(c -> c.getCurrencyCode()).sorted().toList().toString();
    return problem(
        "/journal/mixed-currencies",
        "Journal entry mixes currencies",
        "Postings reference multiple currencies: "
            + codes
            + ". Multi-currency journal entries are not supported in this slice.");
  }

  private static ProblemDetail unbalanced(JournalError.Unbalanced u) {
    return problem(
        "/journal/unbalanced",
        "Journal entry is not balanced",
        "Sum of debits ("
            + u.debits().minorUnits()
            + " "
            + u.debits().currency().getCurrencyCode()
            + ") does not equal sum of credits ("
            + u.credits().minorUnits()
            + " "
            + u.credits().currency().getCurrencyCode()
            + ").");
  }

  private static ProblemDetail journalPostingInClosedPeriod(JournalError.PostingInClosedPeriod p) {
    return problem(
        "/journal/posting-in-closed-period",
        "Posting falls in a closed period",
        "Period " + p.period() + " is closed; reopen it before posting.");
  }

  private static ProblemDetail overflow(JournalError.Overflow o) {
    return problem(
        "/journal/overflow",
        "Posting sum overflowed",
        "Sum of postings on " + o.side() + " side overflowed Long.MAX_VALUE.");
  }

  private static ProblemDetail problem(
      HttpStatus status, String path, String title, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(PROBLEM_BASE + path));
    pd.setTitle(title);
    return pd;
  }

  private static ProblemDetail problem(String path, String title, String detail) {
    return problem(HttpStatus.BAD_REQUEST, path, title, detail);
  }
}
