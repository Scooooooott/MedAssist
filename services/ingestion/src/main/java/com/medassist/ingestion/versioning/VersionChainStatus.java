package com.medassist.ingestion.versioning;

/** Lifecycle status understood by the pure version-chain planner. */
public enum VersionChainStatus {
  ACTIVE,
  SUPERSEDED,
  WITHDRAWN,
  UNKNOWN
}
