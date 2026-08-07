package com.medassist.retrieval.api.dto;

import java.time.LocalDate;
import java.util.Set;

public record RetrievalFiltersDto(
    Set<String> docTypes,
    Set<String> publishers,
    LocalDate effectiveDateFrom,
    LocalDate effectiveDateTo,
    Set<String> sectionTypes) {
  public RetrievalFiltersDto {
    docTypes = docTypes == null ? Set.of() : Set.copyOf(docTypes);
    publishers = publishers == null ? Set.of() : Set.copyOf(publishers);
    sectionTypes = sectionTypes == null ? Set.of() : Set.copyOf(sectionTypes);
  }
}
