package com.medassist.auditgovernance.feedback;

@FunctionalInterface
public interface DeidentifiedTextGuard {
  String requireDeidentified(String text);
}
