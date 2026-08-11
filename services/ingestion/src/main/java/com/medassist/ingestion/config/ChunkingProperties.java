package com.medassist.ingestion.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medassist.chunking")
public class ChunkingProperties implements InitializingBean {
  private int targetTokens = 512;
  private int maxTokens = 1024;
  private int minTokens = 100;
  private int overlapTokens = 50;
  private double semanticBreakpointThreshold = 0.65;
  private String defaultStrategyId = "structure-v1";

  public int getTargetTokens() {
    return targetTokens;
  }

  public void setTargetTokens(final int targetTokens) {
    this.targetTokens = targetTokens;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(final int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public int getMinTokens() {
    return minTokens;
  }

  public void setMinTokens(final int minTokens) {
    this.minTokens = minTokens;
  }

  public int getOverlapTokens() {
    return overlapTokens;
  }

  public void setOverlapTokens(final int overlapTokens) {
    this.overlapTokens = overlapTokens;
  }

  public double getSemanticBreakpointThreshold() {
    return semanticBreakpointThreshold;
  }

  public void setSemanticBreakpointThreshold(final double semanticBreakpointThreshold) {
    this.semanticBreakpointThreshold = semanticBreakpointThreshold;
  }

  public String getDefaultStrategyId() {
    return defaultStrategyId;
  }

  public void setDefaultStrategyId(final String defaultStrategyId) {
    this.defaultStrategyId = defaultStrategyId;
  }

  @Override
  public void afterPropertiesSet() {
    if (targetTokens <= 0 || maxTokens <= 0 || minTokens < 0 || overlapTokens < 0) {
      throw new IllegalArgumentException("chunking token settings are invalid");
    }
    if (targetTokens > maxTokens || minTokens > targetTokens || overlapTokens >= maxTokens) {
      throw new IllegalArgumentException("chunking token relationships are invalid");
    }
    if (!Double.isFinite(semanticBreakpointThreshold)
        || semanticBreakpointThreshold < -1.0
        || semanticBreakpointThreshold > 1.0) {
      throw new IllegalArgumentException("semanticBreakpointThreshold must be between -1 and 1");
    }
    if (defaultStrategyId == null
        || !defaultStrategyId.matches("structure-v1|fixed-v1|semantic-v1")) {
      throw new IllegalArgumentException("defaultStrategyId is not a registered strategy");
    }
  }
}
