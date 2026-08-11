package com.medassist.agent.execution;

import com.medassist.agent.checkpoint.AgentCheckpoint;
import com.medassist.agent.checkpoint.CheckpointPhase;
import com.medassist.agent.checkpoint.CheckpointStore;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.TerminationReason;
import com.medassist.agent.trajectory.TrajectoryEvent;
import com.medassist.agent.trajectory.TrajectoryPhase;
import com.medassist.agent.trajectory.TrajectoryRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultAgentExecutionEngine implements AgentExecutionEngine {
  private final AgentRouter router;
  private final AgentToolExecutor toolExecutor;
  private final DraftGenerator draftGenerator;
  private final DraftVerifier draftVerifier;
  private final CheckpointStore checkpointStore;
  private final TrajectoryRecorder trajectoryRecorder;
  private final AgentExecutionLimits limits;
  private final Clock clock;

  public DefaultAgentExecutionEngine(
      final AgentRouter router,
      final AgentToolExecutor toolExecutor,
      final DraftGenerator draftGenerator,
      final DraftVerifier draftVerifier,
      final CheckpointStore checkpointStore,
      final TrajectoryRecorder trajectoryRecorder,
      final AgentExecutionLimits limits,
      final Clock clock) {
    this.router = Objects.requireNonNull(router, "router");
    this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
    this.draftGenerator = Objects.requireNonNull(draftGenerator, "draftGenerator");
    this.draftVerifier = Objects.requireNonNull(draftVerifier, "draftVerifier");
    this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
    this.trajectoryRecorder = Objects.requireNonNull(trajectoryRecorder, "trajectoryRecorder");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public AgentExecutionResult execute(final AgentState state) {
    Objects.requireNonNull(state, "state");
    final Instant deadline = Instant.now(clock).plus(limits.timeout());
    final AtomicLong sequence = new AtomicLong();
    GeneratedDraft draft = null;
    while (state.terminationReason() == null) {
      if (timedOut(deadline)) {
        state.forceTerminate(TerminationReason.TIMEOUT);
        break;
      }
      if (state.stepCount() >= limits.maxSteps()) {
        state.forceTerminate(TerminationReason.MAX_STEPS);
        break;
      }
      final NodeResult nodeResult = runNode(state, draft, sequence);
      draft = nodeResult.draft();
      if (timedOut(deadline)) {
        state.forceTerminate(TerminationReason.TIMEOUT);
      }
    }
    final String answer =
        state.terminationReason() == TerminationReason.COMPLETED && draft != null
            ? draft.text()
            : null;
    return new AgentExecutionResult(state, answer);
  }

  private NodeResult runNode(
      final AgentState state, final GeneratedDraft currentDraft, final AtomicLong sequence) {
    final AgentNode node = state.currentNode();
    final long started = System.nanoTime();
    state.incrementStep();
    record(state, sequence.incrementAndGet(), node, TrajectoryPhase.ENTRY, 0L);
    checkpoint(state, sequence.incrementAndGet(), CheckpointPhase.ENTRY);
    GeneratedDraft nextDraft = currentDraft;
    try {
      switch (node) {
        case ROUTE -> {
          final RouteDecision decision = router.route(state);
          state.applyRoute(decision.classification(), decision.allowedTools(), decision.nextNode());
        }
        case TOOL -> {
          if (state.allowedTools().isEmpty()) {
            state.transitionTo(AgentNode.ABSTAIN);
            break;
          }
          final ToolExecutionResult result = toolExecutor.execute(state);
          state.applyToolResult(
              result.toolCalls(),
              result.candidateChunks(),
              result.safeProjection().aggregationColumns(),
              result.runtimeSafetyEvidence());
          state.transitionTo(
              result.succeeded() || result.degraded() ? AgentNode.GENERATE : AgentNode.ABSTAIN);
        }
        case GENERATE -> {
          nextDraft =
              draftGenerator.generate(
                  new AgentGenerationContext(
                      state.projection(), state.runtimeSafetyEvidence(), state.chatHistory()));
          state.applyDraft(nextDraft.metadata());
          state.transitionTo(AgentNode.VERIFY);
        }
        case VERIFY -> {
          if (nextDraft == null) {
            state.transitionTo(AgentNode.ABSTAIN);
            break;
          }
          final VerificationResult result = draftVerifier.verify(nextDraft, state);
          state.applyCitationSummary(result.citationSummary());
          if (result.accepted()) {
            state.transitionTo(AgentNode.RESPOND);
          } else if (result.retryable() && state.retryCount() < limits.maxRetries()) {
            state.transitionTo(AgentNode.RETRY);
          } else {
            state.transitionTo(AgentNode.ABSTAIN);
          }
        }
        case RETRY -> {
          state.incrementRetry();
          state.transitionTo(AgentNode.TOOL);
        }
        case RESPOND -> state.terminate(TerminationReason.COMPLETED);
        case ABSTAIN -> state.terminate(TerminationReason.ABSTAINED);
      }
    } catch (final RuntimeException exception) {
      state.forceTerminate(TerminationReason.EXECUTION_ERROR);
    } finally {
      final long durationMillis = (System.nanoTime() - started) / 1_000_000L;
      record(state, sequence.incrementAndGet(), node, TrajectoryPhase.EXIT, durationMillis);
      checkpoint(state, sequence.incrementAndGet(), CheckpointPhase.EXIT);
    }
    return new NodeResult(nextDraft);
  }

  private boolean timedOut(final Instant deadline) {
    return !Instant.now(clock).isBefore(deadline);
  }

  private void record(
      final AgentState state,
      final long sequence,
      final AgentNode node,
      final TrajectoryPhase phase,
      final long durationMillis) {
    trajectoryRecorder.record(
        new TrajectoryEvent(
            state.traceId(),
            sequence,
            node,
            phase,
            state.stepCount(),
            Instant.now(clock),
            durationMillis));
  }

  private void checkpoint(
      final AgentState state, final long sequence, final CheckpointPhase phase) {
    checkpointStore.save(
        new AgentCheckpoint(
            state.traceId() + ":" + sequence,
            state.traceId(),
            state.requestId(),
            sequence,
            phase,
            state.projection(),
            Instant.now(clock)));
  }

  private record NodeResult(GeneratedDraft draft) {}
}
