package com.medassist.agent.state;

import java.io.Serializable;

public record CitationSummary(int candidateCount, int validCount, boolean sufficientEvidence)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public CitationSummary {
    if (candidateCount < 0 || validCount < 0 || validCount > candidateCount) {
      throw new IllegalArgumentException("invalid citation counts");
    }
  }

  public static CitationSummary empty() {
    return new CitationSummary(0, 0, false);
  }
}
