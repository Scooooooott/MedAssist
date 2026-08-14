package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuditTransportPropertiesTest {
  @Test
  void acceptsIndependentDefaultChainConfiguration() {
    assertThatCode(new AuditTransportProperties()::validate).doesNotThrowAnyException();
  }

  @Test
  void rejectsChainFileOutsideConfiguredDirectory() {
    final AuditTransportProperties properties = new AuditTransportProperties();
    properties.getChain().setFile("../producer-outbox.bin");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("simple file name");
  }

  @Test
  void rejectsUnboundedChainRecords() {
    final AuditTransportProperties properties = new AuditTransportProperties();
    properties.getChain().setMaxRecordBytes(16 * 1024 * 1024 + 1);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("safe limits");
  }
}
