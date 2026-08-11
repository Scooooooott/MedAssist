package com.medassist.agent.application;

import java.util.Objects;

@FunctionalInterface
public interface QueryDeidentifier {
  DeidentifiedQuery deidentify(String rawQuery);

  default DeidentifiedQuery deidentify(
      final String rawQuery, final DeidentificationMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    return deidentify(rawQuery);
  }
}
