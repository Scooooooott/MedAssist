package com.medassist.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.checkpoint.InMemoryCheckpointStore;
import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.DraftMetadata;
import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.agent.trajectory.InMemoryTrajectoryRecorder;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultAgentToolExecutorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-10T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void defaultExecutorFailsClosedWithoutBackend() {
    final AgentState state =
        toolState(Role.CLINICIAN, QueryClassification.POLICY, Set.of("policy_search"));

    final ToolExecutionResult result = new DefaultAgentToolExecutor().execute(state);

    assertEquals(ToolExecutionStatus.FAIL_CLOSED, result.status());
    assertFalse(result.succeeded());
    assertTrue(result.toolCalls().isEmpty());
    assertTrue(result.candidateChunks().isEmpty());
  }

  @Test
  void executorRejectsAStateThatContainsAnUnauthorizedTool() {
    final AtomicInteger backendCalls = new AtomicInteger();
    final AgentState state =
        toolState(Role.ADMIN, QueryClassification.CLINICAL, Set.of("clinical_search"));
    final ToolBackend backend =
        request -> {
          backendCalls.incrementAndGet();
          return ToolBackendResult.empty(request.toolName());
        };

    final ToolExecutionResult result =
        new DefaultAgentToolExecutor(new DefaultToolRegistry(), backend).execute(state);

    assertEquals(ToolExecutionStatus.REJECTED, result.status());
    assertEquals(0, backendCalls.get());
  }

  @Test
  void backendContentIsProjectedToMetadataAndAggregationColumnsOnly() {
    final AgentState state =
        toolState(Role.CLINICIAN, QueryClassification.POLICY, Set.of("policy_search"));
    final ToolBackend backend =
        request ->
            new ToolBackendResult(
                request.toolName(),
                List.of(
                    new ToolBackendChunk(
                        UUID.randomUUID(),
                        4,
                        12,
                        "sha256:chunk",
                        0.9,
                        1,
                        "v3",
                        "hospital-policy",
                        "doc-7#p2",
                        "raw patient text and full document content")),
                List.of(new SafeAggregationColumn("count", "4")));

    final ToolExecutionResult result =
        new DefaultAgentToolExecutor(new DefaultToolRegistry(), backend).execute(state);

    assertTrue(result.succeeded());
    assertFalse(result.degraded());
    assertEquals(1, result.safeProjection().chunks().size());
    assertEquals("v3", result.safeProjection().chunks().getFirst().version());
    assertEquals("hospital-policy", result.safeProjection().chunks().getFirst().source());
    assertEquals("doc-7#p2", result.safeProjection().chunks().getFirst().citationLocator());
    assertEquals(
        List.of(new SafeAggregationColumn("count", "4")),
        result.safeProjection().aggregationColumns());
    assertEquals(1, result.candidateChunks().size());
    assertFalse(result.toString().contains("raw patient text"));
  }

  @Test
  void mixedFailureWithUsableSuccessIsMarkedDegraded() {
    final AgentState state =
        mixedState();
    final ToolBackend backend =
        request -> {
          if (DefaultToolRegistry.POLICY_SEARCH.equals(request.toolName())) {
            throw new IllegalStateException("policy unavailable");
          }
          return new ToolBackendResult(
              request.toolName(),
              List.of(
                  new ToolBackendChunk(
                      UUID.randomUUID(),
                      3,
                      9,
                      "sha256:chunk",
                      0.7,
                      1,
                      "v2",
                      "clinical-index",
                      "doc-3#p1",
                      "raw clinical text")),
              List.of(new SafeAggregationColumn("count", "3")));
        };

    final ToolExecutionResult result =
        new DefaultAgentToolExecutor(new DefaultToolRegistry(), backend).execute(state);

    assertEquals(ToolExecutionStatus.DEGRADED, result.status());
    assertTrue(result.degraded());
    assertEquals(2, result.toolCalls().size());
    assertTrue(result.toolCalls().stream().anyMatch(call -> "SUCCEEDED".equals(call.status())));
    assertTrue(result.toolCalls().stream().anyMatch(call -> "FAILED".equals(call.status())));
    assertEquals(1, result.safeProjection().chunks().size());
    assertFalse(result.safeProjection().aggregationColumns().isEmpty());
  }

  @Test
  void failClosedResultStopsGeneration() {
    final AgentState state =
        AgentState.start(
            RequestIds.create(),
            new DeidentifiedQuery("What does the hospital policy say?"),
            Role.CLINICIAN);
    final AtomicInteger generationCalls = new AtomicInteger();
    final DefaultAgentExecutionEngine engine =
        new DefaultAgentExecutionEngine(
            new DefaultAgentRouter(),
            new DefaultAgentToolExecutor(),
            ignored -> {
              generationCalls.incrementAndGet();
              return new GeneratedDraft(
                  "must not be used", new DraftMetadata("sha256:draft", 1, Map.of()));
            },
            (draft, ignored) -> VerificationResult.accepted(CitationSummary.empty()),
            new InMemoryCheckpointStore(),
            new InMemoryTrajectoryRecorder(),
            new AgentExecutionLimits(10, Duration.ofSeconds(5), 0),
            CLOCK);

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.ABSTAINED, result.state().terminationReason());
    assertEquals(0, generationCalls.get());
    assertTrue(result.abstained());
  }

  private static AgentState toolState(
      final Role role, final QueryClassification classification, final Set<String> allowedTools) {
    final AgentState state =
        AgentState.start(
            RequestIds.create(), new DeidentifiedQuery("safe deidentified query"), role);
    state.applyRoute(classification, allowedTools, AgentNode.TOOL);
    return state;
  }

  private static AgentState mixedState() {
    final AgentState state =
        AgentState.start(
            RequestIds.create(),
            new DeidentifiedQuery("policy guidance for a clinical diagnosis"),
            Role.CLINICIAN);
    state.applyRoute(
        QueryClassification.MIXED,
        Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH),
        AgentNode.TOOL);
    return state;
  }
}
