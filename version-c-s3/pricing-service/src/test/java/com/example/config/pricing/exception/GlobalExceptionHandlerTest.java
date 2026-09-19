package com.example.config.pricing.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("handles InvalidPricingRequestException")
  void handlesInvalidPricingRequest() {
    InvalidPricingRequestException ex =
        new InvalidPricingRequestException("Price calculation error");
    ProblemDetail problem = this.handler.onInvalidPricingRequest(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Invalid pricing request");
    assertThat(problem.getDetail()).isEqualTo("Price calculation error");
  }

  @Test
  @DisplayName("handles MethodArgumentNotValidException")
  void handlesMethodArgumentNotValid() {
    BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
    bindingResult.addError(new FieldError("target", "sku", "must not be blank"));

    MethodParameter parameter =
        new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethods()[0], -1);
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(parameter, bindingResult);

    ProblemDetail problem = this.handler.onInvalidBody(ex);
    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Validation failed");
    assertThat(problem.getProperties()).containsKey("fieldErrors");
  }

  @Test
  @DisplayName("handles MethodArgumentTypeMismatchException")
  void handlesTypeMismatch() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException("abc", Integer.class, "count", null, null);

    ProblemDetail problem = this.handler.onTypeMismatch(ex);
    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Invalid parameter format");
    assertThat(problem.getDetail()).contains("count").contains("abc").contains("Integer");
  }

  @Test
  @DisplayName("handles HttpMessageNotReadableException")
  void handlesUnreadableBody() {
    HttpMessageNotReadableException ex =
        new HttpMessageNotReadableException("Malformed JSON", (HttpInputMessage) null);
    ProblemDetail problem = this.handler.onUnreadableBody(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Malformed JSON payload");
  }

  @Test
  @DisplayName("handles NoResourceFoundException")
  void handlesNoResource() {
    NoResourceFoundException ex =
        new NoResourceFoundException(HttpMethod.GET, "/unknown", "Resource not found");
    ProblemDetail problem = this.handler.onNoResource(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Resource not found");
  }

  @Test
  @DisplayName("handles HttpRequestMethodNotSupportedException")
  void handlesMethodNotSupported() {
    HttpRequestMethodNotSupportedException ex =
        new HttpRequestMethodNotSupportedException("DELETE");
    ProblemDetail problem = this.handler.onMethodNotSupported(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    assertThat(problem.getTitle()).isEqualTo("Method not allowed");
  }

  @Test
  @DisplayName("handles HttpMediaTypeNotSupportedException")
  void handlesMediaTypeNotSupported() {
    HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("text/plain");
    ProblemDetail problem = this.handler.onMediaTypeNotSupported(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    assertThat(problem.getTitle()).isEqualTo("Unsupported media type");
  }

  @Test
  @DisplayName("handles generic Exception with errorId")
  void handlesGenericException() {
    Exception ex = new RuntimeException("Unexpected failure");
    ProblemDetail problem = this.handler.onGenericException(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
    assertThat(problem.getProperties()).containsKey("errorId");
  }
}
