package com.medassist.retrieval.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medassist.retrieval")
public class RetrievalProperties {
  private int defaultTopK = 5;
  private int maxTopK = 20;
  private String distanceMetric = "COSINE";
  private String defaultModelName = "bge-m3";
  private String defaultModelVersion = "m1-baseline";
  private Duration llmTimeout = Duration.ofSeconds(30);
  private ModelService modelService = new ModelService();
  private Llm llm = new Llm();

  public int getDefaultTopK() {
    return defaultTopK;
  }

  public void setDefaultTopK(final int defaultTopK) {
    this.defaultTopK = defaultTopK;
  }

  public int getMaxTopK() {
    return maxTopK;
  }

  public void setMaxTopK(final int maxTopK) {
    this.maxTopK = maxTopK;
  }

  public String getDistanceMetric() {
    return distanceMetric;
  }

  public void setDistanceMetric(final String distanceMetric) {
    this.distanceMetric = distanceMetric;
  }

  public String getDefaultModelName() {
    return defaultModelName;
  }

  public void setDefaultModelName(final String defaultModelName) {
    this.defaultModelName = defaultModelName;
  }

  public String getDefaultModelVersion() {
    return defaultModelVersion;
  }

  public void setDefaultModelVersion(final String defaultModelVersion) {
    this.defaultModelVersion = defaultModelVersion;
  }

  public Duration getLlmTimeout() {
    return llmTimeout;
  }

  public void setLlmTimeout(final Duration llmTimeout) {
    this.llmTimeout = llmTimeout;
  }

  public ModelService getModelService() {
    return modelService;
  }

  public void setModelService(final ModelService modelService) {
    this.modelService = modelService;
  }

  public Llm getLlm() {
    return llm;
  }

  public void setLlm(final Llm llm) {
    this.llm = llm;
  }

  public static class ModelService {
    private String endpoint = "";

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(final String endpoint) {
      this.endpoint = endpoint;
    }
  }

  public static class Llm {
    private String provider = "unconfigured";
    private String model = "unconfigured";

    public String getProvider() {
      return provider;
    }

    public void setProvider(final String provider) {
      this.provider = provider;
    }

    public String getModel() {
      return model;
    }

    public void setModel(final String model) {
      this.model = model;
    }
  }
}
