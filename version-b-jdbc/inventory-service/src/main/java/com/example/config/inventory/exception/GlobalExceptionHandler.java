package com.example.config.inventory.exception;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Standardised RFC 9457 {@code ProblemDetail} error responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(OrderQuantityExceededException.class)
  ProblemDetail onQuantityExceeded(OrderQuantityExceededException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    problem.setTitle("Order quantity exceeded");
    problem.setType(URI.create("urn:problem:order-quantity-exceeded"));
    problem.setProperty("requested", ex.getRequested());
    problem.setProperty("allowed", ex.getAllowed());
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail onInvalidBody(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Invalid request");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Validation failed");
    problem.setType(URI.create("urn:problem:validation-failed"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Validation failed");
    problem.setType(URI.create("urn:problem:validation-failed"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }
}
