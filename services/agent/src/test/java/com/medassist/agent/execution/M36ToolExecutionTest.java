package com.medassist.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.QueryClassification;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class M36ToolExecutionTest {
  @Test
  void mixedSearchesStartConcurrentlyAndReturnBothResults() throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final CountDownLatch started = new CountDownLatch(2);
      final ToolBackend backend =
          request -> {
            started.countDown();
            try {
              if (!started.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("mixed tools did not start together");
              }
            } catch (final InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("mixed tool interrupted", exception);
            }
            return ToolBackendResult.empty(request.toolName());
          };

      final ToolExecutionResult result =
          executorFor(backend, Duration.ofSeconds(1), executor).execute(mixedState());

      assertTrue(result.succeeded());
      assertEquals(
          Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH),
          result.toolCalls().stream()
              .map(call -> call.toolName())
              .collect(java.util.stream.Collectors.toSet()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void mixedFailureWithUsableEvidenceIsDegraded() {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final ToolBackend backend =
          request -> {
            if (DefaultToolRegistry.POLICY_SEARCH.equals(request.toolName())) {
              throw new IllegalStateException("policy unavailable");
            }
            return new ToolBackendResult(
                request.toolName(),
                java.util.List.of(
                    new ToolBackendChunk(
                        java.util.UUID.randomUUID(),
                        2,
                        8,
                        "sha256:chunk",
                        0.91,
                        1,
                        "v3",
                        "clinical-index",
                        "doc-9#p2",
                        "safe clinical evidence")),
                java.util.List.of(new SafeAggregationColumn("count", "1")));
          };

      final ToolExecutionResult result =
          executorFor(backend, Duration.ofSeconds(1), executor).execute(mixedState());

      assertEquals(ToolExecutionStatus.DEGRADED, result.status());
      assertEquals(2, result.toolCalls().size());
      assertTrue(result.toolCalls().stream().anyMatch(call -> "SUCCEEDED".equals(call.status())));
      assertTrue(result.toolCalls().stream().anyMatch(call -> "FAILED".equals(call.status())));
      assertTrue(result.degraded());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void mixedFailureWithoutUsableEvidenceStaysFailClosed() {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final ToolBackend backend =
          request -> {
            throw new IllegalStateException("tool unavailable: " + request.toolName());
          };

      final ToolExecutionResult result =
          executorFor(backend, Duration.ofSeconds(1), executor).execute(mixedState());

      assertEquals(ToolExecutionStatus.FAIL_CLOSED, result.status());
      assertEquals(2, result.toolCalls().size());
      assertTrue(result.toolCalls().stream().allMatch(call -> "FAILED".equals(call.status())));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void timeoutCancelsSlowBranchAndReturnsPartialFailure() throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final AtomicBoolean interrupted = new AtomicBoolean();
      final ToolBackend backend =
          request -> {
            if (DefaultToolRegistry.POLICY_SEARCH.equals(request.toolName())) {
              try {
                Thread.sleep(5_000);
              } catch (final InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cancelled", exception);
              }
            }
            return ToolBackendResult.empty(request.toolName());
          };

      final ToolExecutionResult result =
          executorFor(backend, Duration.ofMillis(50), executor).execute(mixedState());

      assertEquals(ToolExecutionStatus.FAIL_CLOSED, result.status());
      assertTrue(result.toolCalls().stream().anyMatch(call -> "TIMEOUT".equals(call.status())));
      assertTrue(
          interrupted.get()
              || result.toolCalls().stream().anyMatch(call -> "SUCCEEDED".equals(call.status())));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void structuredQueryFailsClosedWithoutItsSeparateBackend() {
    final AgentState state =
        AgentState.start(
            RequestIds.create(), new DeidentifiedQuery("count patients by age"), Role.RESEARCHER);
    state.applyRoute(
        QueryClassification.STRUCTURED,
        Set.of(DefaultToolRegistry.STRUCTURED_QUERY),
        AgentNode.TOOL);

    final ToolExecutionResult result = new DefaultAgentToolExecutor().execute(state);

    assertEquals(ToolExecutionStatus.FAIL_CLOSED, result.status());
    assertTrue(result.failureReason().contains("structured_query"));
  }

  private static DefaultAgentToolExecutor executorFor(
      final ToolBackend backend, final Duration timeout, final ExecutorService executor) {
    return new DefaultAgentToolExecutor(
        new DefaultToolRegistry(), backend, null, timeout, executor);
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
