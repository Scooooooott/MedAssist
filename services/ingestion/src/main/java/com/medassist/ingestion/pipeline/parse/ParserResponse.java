package com.medassist.ingestion.pipeline.parse;

import com.medassist.domain.DocumentIR;
import java.util.List;
import java.util.Objects;

/** Parser response without transport-specific types. */
public record ParserResponse(DocumentIR document, ParseStatus status, List<String> warnings) {
  public ParserResponse {
    Objects.requireNonNull(status, "status must not be null");
    warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
  }
}
