package com.medassist.retrieval.application.model;

import java.util.List;

/** A substantive answer assertion and the citations explicitly assigned to it. */
public record CitationAssertion(String text, List<CitationCandidate> citations) {
  public CitationAssertion {
    text = text == null ? "" : text;
    citations = citations == null ? List.of() : List.copyOf(citations);
  }
}
