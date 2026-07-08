package co.embracejoy.accounting.keystone.infrastructure.web;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Translates Bean Validation failures into RFC 9457 {@link ProblemDetail} responses. */
@RestControllerAdvice(annotations = RestController.class)
public class ValidationExceptionHandler {

  private static final String PROBLEM_BASE = "https://embracejoy.co/problems";

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handle(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    pd.setType(URI.create(PROBLEM_BASE + "/validation"));
    pd.setTitle("Request validation failed");
    return pd;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail handle(ConstraintViolationException ex) {
    String detail =
        ex.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .collect(Collectors.joining("; "));
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    pd.setType(URI.create(PROBLEM_BASE + "/validation"));
    pd.setTitle("Request validation failed");
    return pd;
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail handle(MethodArgumentTypeMismatchException ex) {
    String required =
        ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "expected type";
    // Reflect the offending value into the ProblemDetail (defense in depth: cap length and
    // strip control chars so a hostile or oversized query string can't bloat the body or
    // poison logs that record it).
    String detail =
        ex.getName() + ": could not convert '" + sanitize(ex.getValue()) + "' to " + required;
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    pd.setType(URI.create(PROBLEM_BASE + "/validation"));
    pd.setTitle("Request validation failed");
    return pd;
  }

  private static String sanitize(Object value) {
    String raw = String.valueOf(value).replaceAll("\\p{Cntrl}", "?");
    return raw.length() > MAX_REFLECTED_VALUE_LENGTH
        ? raw.substring(0, MAX_REFLECTED_VALUE_LENGTH) + "..."
        : raw;
  }

  private static final int MAX_REFLECTED_VALUE_LENGTH = 64;
}
