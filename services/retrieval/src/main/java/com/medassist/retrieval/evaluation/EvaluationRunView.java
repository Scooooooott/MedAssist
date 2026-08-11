package com.medassist.retrieval.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationRunView(
    UUID id,
    String evalSetVersion,
    String split,
    String codeCommit,
    String modelName,
    String modelVersion,
    String judgeModel,
    long randomSeed,
    Map<String, Object> metrics,
    String resultUri,
    Instant createdAt) {
  @JsonProperty("triple")
  public Map<String, String> triple() {
    return Map.of(
        "evalSetVersion", evalSetVersion,
        "codeCommit", codeCommit,
        "modelVersion", modelVersion);
  }
}
