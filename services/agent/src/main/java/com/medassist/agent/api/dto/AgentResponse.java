package com.medassist.agent.api.dto;

import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.DraftMetadata;
import com.medassist.agent.state.TerminationReason;
import java.util.Objects;

/** Response DTO contains only deidentified-result metadata and transient safe draft output. */
public record AgentResponse(
    String traceId,
    String requestId,
    String answer,
    boolean abstained,
    String abstainReason,
    String queryHash,
    DraftMetadata draftMetadata,
    CitationSummary citationSummary,
    TerminationReason terminationReason) {
  public AgentResponse {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(abstainReason, "abstainReason");
    Objects.requireNonNull(terminationReason, "terminationReason");
    Objects.requireNonNull(citationSummary, "citationSummary");
  }
}
