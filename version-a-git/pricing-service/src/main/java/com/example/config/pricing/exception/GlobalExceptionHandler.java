package com.example.config.pricing.exception;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Standardised RFC 9457 {@link ProblemDetail} global exception handler for the pricing service.
 *
 * <p>Produces structured JSON error payloads with diagnostic error IDs, timestamp, and field-level
 * validation summaries without leaking internal stack traces.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InvalidPricingRequestException.class)
  ProblemDetail onInvalidPricingRequest(InvalidPricingRequestException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Invalid pricing request");
    problem.setType(URI.create("urn:problem:invalid-pricing-request"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail onInvalidBody(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    fe -> fe.getField(),
                    fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                    (msg1, msg2) -> msg1 + "; " + msg2,
                    LinkedHashMap::new));

    String detail =
        fieldErrors.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining(", "));

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Validation failed");
    problem.setType(URI.create("urn:problem:validation-failed"));
    problem.setProperty("fieldErrors", fieldErrors);
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Constraint violation");
    problem.setType(URI.create("urn:problem:constraint-violation"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
    Class<?> requiredType = ex.getRequiredType();
    String typeName = requiredType != null ? requiredType.getSimpleName() : "unknown";
    String detail =
        "Parameter '%s' value '%s' could not be converted to expected type '%s'."
            .formatted(ex.getName(), String.valueOf(ex.getValue()), typeName);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Invalid parameter format");
    problem.setType(URI.create("urn:problem:type-mismatch"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Required request body is missing or unparseable.");
    problem.setTitle("Malformed JSON payload");
    problem.setType(URI.create("urn:problem:malformed-request"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail onNoResource(NoResourceFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setTitle("Resource not found");
    problem.setType(URI.create("urn:problem:resource-not-found"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ProblemDetail onMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    problem.setTitle("Method not allowed");
    problem.setType(URI.create("urn:problem:method-not-allowed"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ProblemDetail onMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
    problem.setTitle("Unsupported media type");
    problem.setType(URI.create("urn:problem:unsupported-media-type"));
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail onGenericException(Exception ex) {
    String errorId = UUID.randomUUID().toString();
    log.error("Unhandled internal exception [errorId={}]: {}", errorId, ex.getMessage(), ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred.");
    problem.setTitle("Internal Server Error");
    problem.setType(URI.create("urn:problem:internal-server-error"));
    problem.setProperty("errorId", errorId);
    problem.setProperty("timestamp", Instant.now().toString());
    return problem;
  }
}
