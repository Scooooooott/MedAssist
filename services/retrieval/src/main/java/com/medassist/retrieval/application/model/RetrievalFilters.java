package com.medassist.retrieval.application.model;

import java.time.LocalDate;
import java.util.Set;

public record RetrievalFilters(
    Set<String> docTypes,
    Set<String> publishers,
    LocalDate effectiveDateFrom,
    LocalDate effectiveDateTo,
    Set<String> sectionTypes) {
  public RetrievalFilters {
    docTypes = docTypes == null ? Set.of() : Set.copyOf(docTypes);
    publishers = publishers == null ? Set.of() : Set.copyOf(publishers);
    sectionTypes = sectionTypes == null ? Set.of() : Set.copyOf(sectionTypes);
  }
}
