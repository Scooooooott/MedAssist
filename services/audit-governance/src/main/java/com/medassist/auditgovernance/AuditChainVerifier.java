package com.medassist.auditgovernance;

import java.util.List;

@FunctionalInterface
public interface AuditChainVerifier {
  AuditChainVerificationResult verify(List<AuditEvent> events);
}
