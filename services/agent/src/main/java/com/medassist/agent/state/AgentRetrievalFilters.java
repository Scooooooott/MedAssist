package com.medassist.agent.state;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** Bounded retrieval metadata that can narrow, but never replace, tool-owned policy filters. */
public record AgentRetrievalFilters(
    Set<String> docTypes,
    Set<String> publishers,
    LocalDate effectiveDateFrom,
    LocalDate effectiveDateTo,
    Set<String> sectionTypes)
    implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final int MAX_VALUES = 20;

  public AgentRetrievalFilters {
    docTypes = validate(docTypes, "docTypes");
    publishers = validate(publishers, "publishers");
    sectionTypes = validate(sectionTypes, "sectionTypes");
    if (effectiveDateFrom != null
        && effectiveDateTo != null
        && effectiveDateFrom.isAfter(effectiveDateTo)) {
      throw new IllegalArgumentException("effective date range is invalid");
    }
  }

  public static AgentRetrievalFilters empty() {
    return new AgentRetrievalFilters(Set.of(), Set.of(), null, null, Set.of());
  }

  public static AgentRetrievalFilters fromLegacy(final Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return empty();
    }
    throw new IllegalArgumentException("legacy retrieval filters must be empty");
  }

  @Override
  public String toString() {
    return "AgentRetrievalFilters[values=<redacted>]";
  }

  private static Set<String> validate(final Set<String> values, final String field) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    if (values.size() > MAX_VALUES
        || values.stream()
            .anyMatch(
                value ->
                    value == null
                        || value.isBlank()
                        || value.length() > 128
                        || !value.matches("[\\p{L}\\p{N} ._:/-]+"))) {
      throw new IllegalArgumentException(field + " contains invalid values");
    }
    return Set.copyOf(values);
  }
}
