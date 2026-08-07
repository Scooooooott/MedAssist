package com.medassist.retrieval.model;

public interface QueryEmbeddingClient {
  QueryEmbedding embed(String query, String modelName, String modelVersion);
}
