package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditChainVerificationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically checks the authoritative chain without repeatedly counting one incident. */
public final class AuditChainIntegrityMonitor {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuditChainIntegrityMonitor.class);

  private final AuditChainStore chainStore;
  private final Counter failures;
  private boolean lastValid = true;
  private Long lastBrokenSequence;

  public AuditChainIntegrityMonitor(
      final AuditChainStore chainStore, final MeterRegistry meterRegistry) {
    this.chainStore = Objects.requireNonNull(chainStore, "chainStore");
    this.failures =
        Objects.requireNonNull(meterRegistry, "meterRegistry")
            .counter("medassist.audit.chain.integrity.failures");
  }

  @Scheduled(fixedDelayString = "${medassist.audit.transport.chain.verification-interval-ms:60000}")
  public synchronized void checkIntegrity() {
    try {
      final AuditChainVerificationResult result = chainStore.verify();
      if (result.valid()) {
        lastValid = true;
        lastBrokenSequence = null;
        return;
      }
      final Long brokenSequence =
          result.brokenSequence().isPresent() ? result.brokenSequence().getAsLong() : null;
      final boolean newFailure =
          lastValid || (brokenSequence != null && !brokenSequence.equals(lastBrokenSequence));
      if (newFailure) {
        failures.increment();
        LOGGER.error("Audit chain integrity verification failed");
      }
      lastValid = false;
      lastBrokenSequence = brokenSequence;
    } catch (final RuntimeException exception) {
      if (lastValid) {
        failures.increment();
        LOGGER.error("Audit chain integrity verification raised an exception", exception);
      }
      lastValid = false;
    }
  }
}
