package com.medassist.agent.state;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public record DraftMetadata(String draftHash, int characterCount, Map<String, String> metadata)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public DraftMetadata {
    Objects.requireNonNull(draftHash, "draftHash");
    Objects.requireNonNull(metadata, "metadata");
    metadata = Map.copyOf(metadata);
    if (characterCount < 0) {
      throw new IllegalArgumentException("characterCount must be non-negative");
    }
  }
}
