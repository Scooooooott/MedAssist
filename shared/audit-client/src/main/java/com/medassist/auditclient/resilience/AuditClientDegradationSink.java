package com.medassist.auditclient.resilience;

import com.medassist.auditclient.AuditEventPublisher;
import com.medassist.auditclient.SafeAuditCategory;
import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import com.medassist.common.resilience.DegradationAuditSink;
import com.medassist.common.resilience.DegradationEvent;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Projects structured degradation outcomes into the metadata-only audit transport. */
public final class AuditClientDegradationSink implements DegradationAuditSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuditClientDegradationSink.class);

  private final AuditEventPublisher publisher;

  public AuditClientDegradationSink(final AuditEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void publish(final DegradationEvent event) {
    try {
      final ExecutionContext context = currentContext();
      final Map<String, String> metadata = new TreeMap<>();
      metadata.put("reason_code", event.code());
      metadata.put("content_domain", event.affectedStage());
      metadata.put("obligation", event.fallbackMode().name());
      if (context != null) {
        metadata.put("trace_id", context.traceId());
        metadata.put("request_id", context.requestId());
      }
      publisher.publish(
          new SafeAuditEvent(
              UUID.randomUUID(),
              event.occurredAt(),
              "system",
              SafeAuditCategory.SYSTEM,
              "SYSTEM",
              "degradation.recorded",
              "resilience_component",
              event.component().name(),
              "degraded",
              metadata));
    } catch (final RuntimeException exception) {
      // The resilience decision is already recorded in metrics and tracing; audit transport
      // pressure must remain observable without turning a safe degradation into a new failure.
      LOGGER.warn("degradation audit publication failed", exception);
    }
  }

  private static ExecutionContext currentContext() {
    try {
      return ContextCarrier.requireCurrent();
    } catch (final RuntimeException exception) {
      return null;
    }
  }
}
