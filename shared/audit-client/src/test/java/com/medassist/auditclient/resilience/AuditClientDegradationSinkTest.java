package com.medassist.auditclient.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.auditclient.AuditEventPublisher;
import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.common.resilience.DegradationEvent;
import com.medassist.common.resilience.FallbackMode;
import com.medassist.common.resilience.ResilienceComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditClientDegradationSinkTest {
  @Test
  void publishesOnlyWhitelistedDegradationMetadata() {
    final List<SafeAuditEvent> published = new ArrayList<>();
    final AuditEventPublisher publisher = published::add;
    final AuditClientDegradationSink sink = new AuditClientDegradationSink(publisher);

    sink.publish(
        new DegradationEvent(
            ResilienceComponent.RERANK,
            "RERANK_TIMEOUT",
            "RERANK",
            FallbackMode.ORIGINAL_ORDER,
            "raw reason must not cross the audit boundary",
            Instant.parse("2026-08-11T12:00:00Z")));

    assertThat(published).hasSize(1);
    assertThat(published.getFirst().safeMetadata())
        .containsEntry("reason_code", "RERANK_TIMEOUT")
        .containsEntry("content_domain", "RERANK")
        .containsEntry("obligation", "ORIGINAL_ORDER")
        .doesNotContainKey("reason");
  }
}
