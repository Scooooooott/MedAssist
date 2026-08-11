package com.medassist.ingestion.pipeline.scan;

/** Detects residual PHI without retaining the scanned text in its response. */
@FunctionalInterface
public interface PhiDetectionPort {
  PhiDetectionResponse detect(PhiDetectionRequest request) throws PhiDetectionException;
}
