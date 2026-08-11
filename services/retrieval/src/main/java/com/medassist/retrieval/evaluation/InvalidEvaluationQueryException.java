package com.medassist.retrieval.evaluation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class InvalidEvaluationQueryException extends RuntimeException {
  public InvalidEvaluationQueryException(final String message) {
    super(message);
  }
}
