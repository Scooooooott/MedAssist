package com.medassist.clinicaldata.research;

import java.util.Map;
import java.util.Objects;

public record ResearchViewQuery(
    ResearchView view,
    Map<String, String> filters,
    boolean clinicalExemption,
    String exemptionReason) {
  public ResearchViewQuery {
    Objects.requireNonNull(view, "view");
    filters = Map.copyOf(filters == null ? Map.of() : filters);
    if (clinicalExemption && (exemptionReason == null || exemptionReason.isBlank())) {
      throw new IllegalArgumentException("clinical exemption requires an audit reason");
    }
  }

  public static ResearchViewQuery researcher(final ResearchView view) {
    return new ResearchViewQuery(view, Map.of(), false, null);
  }
}
