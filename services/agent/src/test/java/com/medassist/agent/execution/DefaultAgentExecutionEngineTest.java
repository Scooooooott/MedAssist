package com.medassist.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.checkpoint.CheckpointStore;
import com.medassist.agent.checkpoint.InMemoryCheckpointStore;
import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.DraftMetadata;
import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.agent.trajectory.InMemoryTrajectoryRecorder;
import com.medassist.agent.trajectory.TrajectoryRecorder;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultAgentExecutionEngineTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-10T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void maxStepsForcesTerminationAndStillCheckpointsEnteredNode() {
    final AgentState state = state();
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final InMemoryTrajectoryRecorder trajectory = new InMemoryTrajectoryRecorder();
    final DefaultAgentExecutionEngine engine =
        engine(checkpoints, trajectory, new AgentExecutionLimits(1, Duration.ofSeconds(5), 0));

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.MAX_STEPS, result.state().terminationReason());
    assertEquals(2, checkpoints.history(state.traceId()).size());
    assertEquals(2, trajectory.events(state.traceId()).size());
    assertEquals(AgentNode.ROUTE, trajectory.events(state.traceId()).get(0).node());
  }

  @Test
  void timeoutForcesTerminationBeforeEnteringANode() {
    final AgentState state = state();
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final InMemoryTrajectoryRecorder trajectory = new InMemoryTrajectoryRecorder();
    final DefaultAgentExecutionEngine engine =
        engine(checkpoints, trajectory, new AgentExecutionLimits(10, Duration.ZERO, 0));

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.TIMEOUT, result.state().terminationReason());
    assertTrue(checkpoints.history(state.traceId()).isEmpty());
    assertTrue(trajectory.events(state.traceId()).isEmpty());
  }

  @Test
  void explicitGraphCanCompleteWithOneToolCallPerToolNode() {
    final AgentState state = state();
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final InMemoryTrajectoryRecorder trajectory = new InMemoryTrajectoryRecorder();
    final AtomicInteger toolCalls = new AtomicInteger();
    final DefaultAgentExecutionEngine engine =
        new DefaultAgentExecutionEngine(
            ignored -> new RouteDecision(QueryClassification.MIXED, Set.of("test"), AgentNode.TOOL),
            ignored -> {
              toolCalls.incrementAndGet();
              return ToolExecutionResult.empty();
            },
            ignored ->
                new GeneratedDraft(
                    "deidentified answer", new DraftMetadata("sha256:draft", 20, Map.of())),
            (draft, ignored) -> VerificationResult.accepted(new CitationSummary(1, 1, true)),
            checkpoints,
            trajectory,
            new AgentExecutionLimits(8, Duration.ofSeconds(5), 0),
            CLOCK);

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.COMPLETED, result.state().terminationReason());
    assertEquals("deidentified answer", result.answer());
    assertEquals(1, toolCalls.get());
    assertEquals(10, checkpoints.history(state.traceId()).size());
    assertEquals(10, trajectory.events(state.traceId()).size());
  }

  @Test
  void degradedMixedToolResultContinuesToGenerate() {
    final AgentState state = mixedState();
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final InMemoryTrajectoryRecorder trajectory = new InMemoryTrajectoryRecorder();
    final AtomicInteger generationCalls = new AtomicInteger();
    final DefaultAgentExecutionEngine engine =
        new DefaultAgentExecutionEngine(
            ignored ->
                new RouteDecision(
                    QueryClassification.MIXED,
                    Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH),
                    AgentNode.TOOL),
            new DefaultAgentToolExecutor(
                new DefaultToolRegistry(),
                request -> {
                  if (DefaultToolRegistry.POLICY_SEARCH.equals(request.toolName())) {
                    throw new IllegalStateException("policy unavailable");
                  }
                  return new ToolBackendResult(
                      request.toolName(),
                      java.util.List.of(
                          new ToolBackendChunk(
                              java.util.UUID.randomUUID(),
                              3,
                              11,
                              "sha256:chunk",
                              0.8,
                              1,
                              "v4",
                              "clinical-index",
                              "doc-11#p3",
                              "safe clinical excerpt")),
                      java.util.List.of(new SafeAggregationColumn("count", "3")));
                }),
            ignored -> {
              generationCalls.incrementAndGet();
              return new GeneratedDraft(
                  "degraded answer", new DraftMetadata("sha256:draft", 21, Map.of()));
            },
            (draft, ignored) -> VerificationResult.accepted(new CitationSummary(1, 1, true)),
            checkpoints,
            trajectory,
            new AgentExecutionLimits(8, Duration.ofSeconds(5), 0),
            CLOCK);

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.COMPLETED, result.state().terminationReason());
    assertEquals("degraded answer", result.answer());
    assertEquals(1, generationCalls.get());
    assertEquals(2, result.state().toolCalls().size());
    assertEquals(1, result.state().candidateChunks().size());
    assertFalse(result.state().runtimeSafetyEvidence().chunks().isEmpty());
  }

  @Test
  void aggregateOnlyToolResultReachesGenerationWithSafeAggregationColumns() {
    final AgentState state = mixedState();
    final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
    final InMemoryTrajectoryRecorder trajectory = new InMemoryTrajectoryRecorder();
    final AtomicReference<java.util.List<SafeAggregationColumn>> capturedAggregationColumns =
        new AtomicReference<>();
    final DefaultAgentExecutionEngine engine =
        new DefaultAgentExecutionEngine(
            ignored ->
                new RouteDecision(
                    QueryClassification.MIXED,
                    Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH),
                    AgentNode.TOOL),
            ignored ->
                ToolExecutionResult.partialFailure(
                    List.of(),
                    List.of(),
                    "structured query produced only aggregates",
                    new SafeToolResultProjection(
                        List.of(), List.of(new SafeAggregationColumn("count", "4")))),
            context -> {
              capturedAggregationColumns.set(context.state().aggregationColumns());
              return new GeneratedDraft(
                  "There are 4 confirmed cases",
                  new DraftMetadata("sha256:draft", 27, Map.of()),
                  "{\"answer\":\"There are 4 confirmed cases\",\"citations\":[]}");
            },
            new StructuredDraftVerifier()::verify,
            checkpoints,
            trajectory,
            new AgentExecutionLimits(8, Duration.ofSeconds(5), 0),
            CLOCK);

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.COMPLETED, result.state().terminationReason());
    assertEquals("There are 4 confirmed cases", result.answer());
    assertEquals(1, capturedAggregationColumns.get().size());
    assertEquals("4", capturedAggregationColumns.get().getFirst().value());
    assertEquals(1, result.state().aggregationColumns().size());
    assertEquals("4", result.state().aggregationColumns().getFirst().value());
  }

  @Test
  void fullyFailedMixedToolResultStillAbstains() {
    final AgentState state = mixedState();
    final AtomicInteger generationCalls = new AtomicInteger();
    final DefaultAgentExecutionEngine engine =
        new DefaultAgentExecutionEngine(
            ignored ->
                new RouteDecision(
                    QueryClassification.MIXED,
                    Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH),
                    AgentNode.TOOL),
            new DefaultAgentToolExecutor(
                new DefaultToolRegistry(),
                request -> {
                  throw new IllegalStateException(
                      "tool unavailable: " + request.toolName());
                }),
            ignored -> {
              generationCalls.incrementAndGet();
              return new GeneratedDraft(
                  "should not generate", new DraftMetadata("sha256:draft", 21, Map.of()));
            },
            (draft, ignored) -> VerificationResult.accepted(new CitationSummary(1, 1, true)),
            new InMemoryCheckpointStore(),
            new InMemoryTrajectoryRecorder(),
            new AgentExecutionLimits(8, Duration.ofSeconds(5), 0),
            CLOCK);

    final AgentExecutionResult result = engine.execute(state);

    assertEquals(TerminationReason.ABSTAINED, result.state().terminationReason());
    assertEquals(0, generationCalls.get());
    assertTrue(result.abstained());
    assertTrue(result.state().toolCalls().stream().allMatch(call -> "FAILED".equals(call.status())));
  }

  private AgentState state() {
    return AgentState.start(
        RequestIds.create(), new DeidentifiedQuery("safe query"), Role.CLINICIAN);
  }

  private AgentState mixedState() {
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

  private DefaultAgentExecutionEngine engine(
      final CheckpointStore checkpoints,
      final TrajectoryRecorder trajectory,
      final AgentExecutionLimits limits) {
    return new DefaultAgentExecutionEngine(
        ignored -> new RouteDecision(QueryClassification.MIXED, Set.of(), AgentNode.TOOL),
        ignored -> ToolExecutionResult.empty(),
        ignored -> new GeneratedDraft("answer", new DraftMetadata("sha256:draft", 6, Map.of())),
        (draft, ignored) -> VerificationResult.accepted(CitationSummary.empty()),
        checkpoints,
        trajectory,
        limits,
        CLOCK);
  }
}
