package com.medassist.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.agent.state.QueryClassification;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentThreadContextTest {
  @Test
  void contextIsRestoredAndRemovedAfterTaskCompletes() {
    final RequestIds ids = RequestIds.create();
    final ToolInvocationRequest request = request(ids, "policy_search", QueryClassification.POLICY);

    assertThat(AgentThreadContext.current()).isEmpty();
    final String traceId =
        AgentThreadContext.with(
            request, () -> AgentThreadContext.current().orElseThrow().traceId());

    assertThat(traceId).isEqualTo(ids.traceId());
    assertThat(AgentThreadContext.current()).isEmpty();
  }

  @Test
  void nestedTaskRestoresOuterContext() {
    final RequestIds outerIds = RequestIds.create();
    final RequestIds innerIds = RequestIds.create();
    final ToolInvocationRequest outer =
        request(outerIds, "policy_search", QueryClassification.POLICY);
    final ToolInvocationRequest inner =
        request(innerIds, "clinical_search", QueryClassification.CLINICAL);

    AgentThreadContext.with(
        outer,
        () -> {
          assertThat(AgentThreadContext.current())
              .get()
              .extracting(AgentThreadContext.Context::traceId)
              .isEqualTo(outerIds.traceId());
          AgentThreadContext.with(
              inner,
              () ->
                  assertThat(AgentThreadContext.current())
                      .get()
                      .extracting(AgentThreadContext.Context::traceId)
                      .isEqualTo(innerIds.traceId()));
          assertThat(AgentThreadContext.current())
              .get()
              .extracting(AgentThreadContext.Context::traceId)
              .isEqualTo(outerIds.traceId());
          return null;
        });

    assertThat(AgentThreadContext.current()).isEmpty();
  }

  private static ToolInvocationRequest request(
      final RequestIds ids, final String toolName, final QueryClassification classification) {
    return new ToolInvocationRequest(
        toolName,
        "safe query",
        "sha256:query",
        Role.CLINICIAN,
        classification,
        5,
        Map.of(),
        ids.traceId(),
        ids.requestId());
  }
}
