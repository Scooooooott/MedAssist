package com.medassist.ingestion.pipeline.parse;

import com.medassist.domain.PhiEntity;
import java.util.List;
import java.util.Objects;

/** De-identification response; entities intentionally carry no detected values. */
public record DeidentificationResponse(
    String text, List<PhiEntity> entities, String policyVersion) {
  public DeidentificationResponse {
    Objects.requireNonNull(text, "text must not be null");
    entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
    Objects.requireNonNull(policyVersion, "policyVersion must not be null");
    if (policyVersion.isBlank()) {
      throw new IllegalArgumentException("policyVersion must not be blank");
    }
  }
}
