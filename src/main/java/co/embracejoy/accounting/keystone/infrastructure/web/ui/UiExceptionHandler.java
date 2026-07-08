package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps the two framework-thrown exceptions admin UI controllers can't dispatch inline — Bean
 * Validation failure and Spring Security's {@code @PreAuthorize} denial — to the same {@code
 * fragments/alert :: alert} view the domain-error paths render. Scoped to {@code
 * infrastructure.web.ui} via {@code basePackages}, and complemented by {@code
 * SecurityExceptionHandler}/{@code ValidationExceptionHandler} restricting themselves to
 * {@code @RestController}-annotated beans (via {@code @RestControllerAdvice(annotations =
 * RestController.class)}). UI controllers are plain {@code @Controller}s, not
 * {@code @RestController}s, so those two JSON-API advisors never match them — this advice is the
 * only one left to handle these exception types for the UI package.
 *
 * <p>Per ADR-0004, domain errors (surfaced as {@code Result<T, E>}) are adjudicated inline by each
 * controller via {@code UiResultMapper} — not here. This handler exists only for the two exceptions
 * the framework throws before a controller method body ever runs, so there's nothing for the
 * controller to adjudicate.
 */
@ControllerAdvice(basePackages = "co.embracejoy.accounting.keystone.infrastructure.web.ui")
public class UiExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public String onValidationError(
      MethodArgumentNotValidException ex,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    String detail =
        ex.getBindingResult().getAllErrors().stream()
            .map(err -> err.getDefaultMessage())
            .filter(msg -> msg != null)
            .findFirst()
            .orElse("Request is invalid.");
    model.addAttribute("alert", new AlertView("warning", "Invalid input", detail));
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    // Mirror onAccessDenied: HTMX gets the bare fragment for #alert-region swap;
    // a plain browser POST (progressive-enhancement fallback) gets a full error page
    // so axe's document-title/html-has-lang checks still pass at the top-level nav.
    return isHtmxRequest(request) ? "fragments/alert :: alert" : "error";
  }

  @ExceptionHandler(AccessDeniedException.class)
  public String onAccessDenied(
      AccessDeniedException ex,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    model.addAttribute(
        "alert", new AlertView("danger", "Not allowed", "This action requires a higher role."));
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    return isHtmxRequest(request) ? "fragments/alert :: alert" : "error";
  }

  /**
   * Every HTMX-driven mutation in this app ({@code hx-post}/{@code hx-put}/{@code hx-delete} forms)
   * sends {@code HX-Request: true} and expects the bare alert fragment its own swap target can
   * receive. A denial reached via a plain browser navigation — e.g. a nav link into a page the
   * caller's role doesn't cover — has no such target and needs a complete, accessible document
   * instead ({@code error.html}, which wraps the same alert fragment in {@code layout.html}):
   * {@code AdminUiE2EIT#bookkeeperSeesForbidden} caught the bare fragment failing axe-core's {@code
   * document-title}/{@code html-has-lang} WCAG 2A checks when served as a full navigation response
   * with no surrounding {@code <html lang>}/{@code <title>}.
   */
  private static boolean isHtmxRequest(HttpServletRequest request) {
    return "true".equalsIgnoreCase(request.getHeader("HX-Request"));
  }
}
