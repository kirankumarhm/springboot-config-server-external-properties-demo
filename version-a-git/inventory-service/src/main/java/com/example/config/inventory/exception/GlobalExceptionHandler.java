package com.example.config.inventory.exception;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error into an RFC 9457 {@link ProblemDetail} ({@code application/problem+json}).
 * Internal details and stack traces are logged, never returned; an unexpected error carries an
 * {@code errorId} that matches the log line.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail onNotFound(NoResourceFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND, "Resource not found", "No endpoint " + ex.getResourcePath());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ProblemDetail onMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ex.getMessage());
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ProblemDetail onNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
    return problem(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", "This API only produces JSON");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail onUnexpected(Exception ex) {
    String errorId = UUID.randomUUID().toString();
    log.error("Unexpected error [errorId={}]", errorId, ex);
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error",
            "An unexpected error occurred.");
    problem.setProperty("errorId", errorId);
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setType(URI.create("urn:problem:" + title.toLowerCase(Locale.ROOT).replace(' ', '-')));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }
}
