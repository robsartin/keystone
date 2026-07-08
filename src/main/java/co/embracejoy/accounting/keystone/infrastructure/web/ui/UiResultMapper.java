package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Translates domain errors into {@link AlertView} fragments (and their matching HTTP status) for
 * the admin UI, mirroring {@link ResultMapper}'s JSON {@link ProblemDetail} translation. Consumed
 * by the mutation handlers landing in T7/T8; T6 only ships the API.
 */
public final class UiResultMapper {

  private UiResultMapper() {
    // static utility class; no instances
  }

  public static AlertView toAlertView(SecurityError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return new AlertView(severityFor(pd.getStatus()), pd.getTitle(), pd.getDetail());
  }

  public static AlertView toAlertView(TenantError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return new AlertView(severityFor(pd.getStatus()), pd.getTitle(), pd.getDetail());
  }

  public static HttpStatus statusFor(SecurityError err) {
    return HttpStatus.valueOf(ResultMapper.toProblemDetail(err).getStatus());
  }

  public static HttpStatus statusFor(TenantError err) {
    return HttpStatus.valueOf(ResultMapper.toProblemDetail(err).getStatus());
  }

  private static String severityFor(int status) {
    if (status >= 500) {
      return "danger";
    }
    if (status >= 400) {
      return "warning";
    }
    return "info";
  }
}
