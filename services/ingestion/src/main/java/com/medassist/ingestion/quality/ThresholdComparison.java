package com.medassist.ingestion.quality;

/** Supported configured threshold directions. */
public enum ThresholdComparison {
  AT_LEAST,
  AT_MOST;

  boolean accepts(final double actual, final double threshold) {
    return this == AT_LEAST ? actual >= threshold : actual <= threshold;
  }
}
