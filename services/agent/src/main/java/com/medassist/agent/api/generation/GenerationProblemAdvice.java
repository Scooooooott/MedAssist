package com.medassist.agent.api.generation;

import com.medassist.agent.generation.GenerationException;
import com.medassist.agent.generation.GenerationStoreException;
import com.medassist.common.context.InvalidExecutionContextException;
import com.medassist.common.context.MissingExecutionContextException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GenerationController.class)
public final class GenerationProblemAdvice {
  @ExceptionHandler(GenerationException.class)
  ResponseEntity<ProblemDetail> generation(final GenerationException exception) {
    return switch (exception.reason()) {
      case INVALID_REQUEST ->
          problem(HttpStatus.BAD_REQUEST, "GENERATION_INVALID_REQUEST", exception.getMessage());
      case NOT_FOUND ->
          problem(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", exception.getMessage());
      case FORBIDDEN ->
          problem(HttpStatus.FORBIDDEN, "GENERATION_FORBIDDEN", exception.getMessage());
      case EXPIRED -> problem(HttpStatus.GONE, "GENERATION_EXPIRED", exception.getMessage());
      case TERMINAL_CONFLICT ->
          problem(HttpStatus.CONFLICT, "GENERATION_TERMINAL", exception.getMessage());
    };
  }

  @ExceptionHandler(GenerationStoreException.class)
  ResponseEntity<ProblemDetail> store(final GenerationStoreException exception) {
    return switch (exception.reason()) {
      case IDEMPOTENCY_CONFLICT ->
          problem(HttpStatus.CONFLICT, "GENERATION_IDEMPOTENCY_CONFLICT", exception.getMessage());
      case ACTIVE_LIMIT -> rateLimited();
      case EVENT_LIMIT, BYTE_LIMIT ->
          problem(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "GENERATION_BUFFER_LIMIT",
              "generation event buffer limit reached");
      case UNAVAILABLE ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "GENERATION_STORE_UNAVAILABLE",
              "generation storage is unavailable");
    };
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ProblemDetail> invalidRequest(final Exception exception) {
    return problem(
        HttpStatus.BAD_REQUEST, "GENERATION_INVALID_REQUEST", "generation request is invalid");
  }

  @ExceptionHandler(MissingExecutionContextException.class)
  ResponseEntity<ProblemDetail> missingContext(final MissingExecutionContextException exception) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "GENERATION_AUTHENTICATION_REQUIRED",
        "authentication is required");
  }

  @ExceptionHandler(InvalidExecutionContextException.class)
  ResponseEntity<ProblemDetail> invalidContext(final InvalidExecutionContextException exception) {
    return problem(
        HttpStatus.FORBIDDEN, "GENERATION_FORBIDDEN", "generation session access is forbidden");
  }

  private static ResponseEntity<ProblemDetail> rateLimited() {
    final ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.TOO_MANY_REQUESTS,
            "GENERATION_ACTIVE_LIMIT",
            "generation active-session limit reached");
    final HttpHeaders headers = new HttpHeaders();
    headers.putAll(response.getHeaders());
    headers.set(HttpHeaders.RETRY_AFTER, "1");
    return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
  }

  private static ResponseEntity<ProblemDetail> problem(
      final HttpStatus status, final String code, final String detail) {
    final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("urn:medassist:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    return ResponseEntity.status(status).body(problem);
  }
}
