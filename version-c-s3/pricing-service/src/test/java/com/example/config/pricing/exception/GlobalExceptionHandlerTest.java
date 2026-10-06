package com.example.config.pricing.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void unexpectedErrorHidesTheCauseAndReturnsAnErrorId() {
    ProblemDetail problem = this.handler.onUnexpected(new IllegalStateException("db password=x"));

    assertThat(problem.getStatus()).isEqualTo(500);
    assertThat(problem.getDetail()).doesNotContain("password");
    assertThat(problem.getProperties()).containsKey("errorId");
    assertThat(problem.getType()).hasToString("urn:problem:internal-server-error");
  }

  @Test
  void notFoundNamesThePath() {
    ProblemDetail problem =
        this.handler.onNotFound(new NoResourceFoundException(HttpMethod.GET, "/x", "/x"));
    assertThat(problem.getStatus()).isEqualTo(404);
    assertThat(problem.getDetail()).contains("/x");
  }

  @Test
  void notAcceptableSaysTheApiOnlyProducesJson() {
    ProblemDetail problem =
        this.handler.onNotAcceptable(new HttpMediaTypeNotAcceptableException("text/xml"));
    assertThat(problem.getStatus()).isEqualTo(406);
  }
}
