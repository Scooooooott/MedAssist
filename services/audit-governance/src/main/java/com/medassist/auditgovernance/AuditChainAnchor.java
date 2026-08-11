package com.medassist.auditgovernance;

/** Boundary for a future external immutable anchor; this domain module performs no network I/O. */
@FunctionalInterface
public interface AuditChainAnchor {
  void anchor(String chainHash, long sequence);
}
