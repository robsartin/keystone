package co.embracejoy.accounting.keystone.infrastructure.web;

import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates Bean Validation failures into RFC 9457 {@link ProblemDetail} responses. */
@RestControllerAdvice
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
}
