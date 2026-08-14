package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.auditgovernance.AuditChainAnchor;
import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditChainVerificationResult;
import com.medassist.auditgovernance.AuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainIntegrityMonitorTest {
  @Test
  void registersZeroCounterAndDoesNotRepeatOneIntegrityIncident() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MutableChainStore chain = new MutableChainStore();
    final AuditChainIntegrityMonitor monitor = new AuditChainIntegrityMonitor(chain, registry);

    assertThat(failures(registry)).isZero();
    monitor.checkIntegrity();
    assertThat(failures(registry)).isZero();

    chain.result = broken(2);
    monitor.checkIntegrity();
    monitor.checkIntegrity();
    assertThat(failures(registry)).isEqualTo(1);

    chain.result = broken(3);
    monitor.checkIntegrity();
    assertThat(failures(registry)).isEqualTo(2);

    chain.failure = new IllegalStateException("disk unavailable");
    monitor.checkIntegrity();
    monitor.checkIntegrity();
    assertThat(failures(registry)).isEqualTo(2);

    chain.failure = null;
    chain.result = AuditChainVerificationResult.valid(0);
    monitor.checkIntegrity();
    chain.failure = new IllegalStateException("disk unavailable");
    monitor.checkIntegrity();
    monitor.checkIntegrity();
    assertThat(failures(registry)).isEqualTo(3);
  }

  private static double failures(final SimpleMeterRegistry registry) {
    return registry.get("medassist.audit.chain.integrity.failures").counter().count();
  }

  private static AuditChainVerificationResult broken(final long sequence) {
    return AuditChainVerificationResult.broken(
        sequence, UUID.randomUUID(), sequence - 1, "test failure");
  }

  private static final class MutableChainStore implements AuditChainStore {
    private AuditChainVerificationResult result = AuditChainVerificationResult.valid(0);
    private RuntimeException failure;

    @Override
    public AuditEvent publish(final AuditEvent event) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AuditEvent> events() {
      return List.of();
    }

    @Override
    public AuditChainVerificationResult verify() {
      if (failure != null) {
        throw failure;
      }
      return result;
    }

    @Override
    public String lastHash() {
      return "";
    }

    @Override
    public void anchor(final AuditChainAnchor anchor) {}

    @Override
    public boolean contains(final UUID eventId) {
      return false;
    }

    @Override
    public void markProcessed(final UUID eventId) {}
  }
}
