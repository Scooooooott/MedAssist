package com.medassist.agent.execution;

import com.medassist.agent.state.DraftMetadata;
import java.util.Objects;

/** The draft is transient execution data; only its metadata enters AgentState. */
public record GeneratedDraft(String text, DraftMetadata metadata, String structuredResponse) {
  public GeneratedDraft(final String text, final DraftMetadata metadata) {
    this(text, metadata, text);
  }

  public GeneratedDraft {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(structuredResponse, "structuredResponse");
  }

  @Override
  public String toString() {
    return "GeneratedDraft[text=<redacted>, metadata=" + metadata + "]";
  }
}
