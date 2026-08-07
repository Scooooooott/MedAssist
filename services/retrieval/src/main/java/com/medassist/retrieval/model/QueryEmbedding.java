package com.medassist.retrieval.model;

import java.util.List;

public record QueryEmbedding(
    String modelName, String modelVersion, List<Float> vector, long elapsedMs) {
  public QueryEmbedding {
    vector = List.copyOf(vector);
  }
}
