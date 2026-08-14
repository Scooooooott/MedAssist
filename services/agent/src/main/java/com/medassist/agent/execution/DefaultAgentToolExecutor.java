package com.medassist.agent.execution;

import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.routing.ToolRegistry;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.ChunkCandidateMetadata;
import com.medassist.agent.state.ToolCallRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes role-scoped tools and projects every backend result before state mutation. */
public final class DefaultAgentToolExecutor implements AgentToolExecutor {
  private static final int DEFAULT_TOP_K = 10;
  private static final int RETRY_TOP_K_INCREMENT = 10;
  private static final Duration DEFAULT_PER_CALL_TIMEOUT = Duration.ofMillis(500);
  private static final Executor DIRECT_EXECUTOR = Runnable::run;
  private static final Set<String> MIXED_SEARCH_TOOLS =
      Set.of(DefaultToolRegistry.POLICY_SEARCH, DefaultToolRegistry.CLINICAL_SEARCH);

  private final ToolRegistry toolRegistry;
  private final ToolBackend backend;
  private final ToolBackend structuredQueryBackend;
  private final Duration perCallTimeout;
  private final Executor executor;
  private final Semaphore bulkhead;
  private final Counter unauthorizedToolAccess;

  public DefaultAgentToolExecutor() {
    this(new DefaultToolRegistry(), null, null, DEFAULT_PER_CALL_TIMEOUT, DIRECT_EXECUTOR);
  }

  public DefaultAgentToolExecutor(final ToolRegistry toolRegistry) {
    this(toolRegistry, null, null, DEFAULT_PER_CALL_TIMEOUT, DIRECT_EXECUTOR);
  }

  public DefaultAgentToolExecutor(final ToolRegistry toolRegistry, final ToolBackend backend) {
    this(toolRegistry, backend, null, DEFAULT_PER_CALL_TIMEOUT, DIRECT_EXECUTOR);
  }

  public DefaultAgentToolExecutor(
      final ToolRegistry toolRegistry,
      final ToolBackend backend,
      final ToolBackend structuredQueryBackend,
      final Duration perCallTimeout,
      final Executor executor) {
    this(toolRegistry, backend, structuredQueryBackend, perCallTimeout, executor, 64);
  }

  public DefaultAgentToolExecutor(
      final ToolRegistry toolRegistry,
      final ToolBackend backend,
      final ToolBackend structuredQueryBackend,
      final Duration perCallTimeout,
      final Executor executor,
      final int maxConcurrentCalls) {
    this(
        toolRegistry,
        backend,
        structuredQueryBackend,
        perCallTimeout,
        executor,
        maxConcurrentCalls,
        null);
  }

  public DefaultAgentToolExecutor(
      final ToolRegistry toolRegistry,
      final ToolBackend backend,
      final ToolBackend structuredQueryBackend,
      final Duration perCallTimeout,
      final Executor executor,
      final int maxConcurrentCalls,
      final MeterRegistry meterRegistry) {
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    this.backend = backend;
    this.structuredQueryBackend = structuredQueryBackend;
    this.perCallTimeout = requirePositive(perCallTimeout, "perCallTimeout");
    this.executor = Objects.requireNonNull(executor, "executor");
    if (maxConcurrentCalls < 1) {
      throw new IllegalArgumentException("maxConcurrentCalls must be positive");
    }
    this.bulkhead = new Semaphore(maxConcurrentCalls);
    this.unauthorizedToolAccess =
        meterRegistry == null
            ? null
            : meterRegistry.counter("medassist.security.unauthorized.tool.access");
  }

  @Override
  public ToolExecutionResult execute(final AgentState state) {
    Objects.requireNonNull(state, "state");
    if (state.currentNode() != AgentNode.TOOL) {
      return ToolExecutionResult.rejected("tool execution requested outside TOOL node");
    }
    if (state.allowedTools().isEmpty()) {
      return ToolExecutionResult.failClosed("no role-scoped tools are available");
    }
    final Set<String> expectedTools =
        toolRegistry.toolsFor(state.role(), state.classification(), state.deidentifiedQuery());
    if (!expectedTools.containsAll(state.allowedTools())) {
      if (unauthorizedToolAccess != null) {
        unauthorizedToolAccess.increment();
      }
      return ToolExecutionResult.rejected(
          "state contains a tool outside its role-scoped allowlist");
    }
    if (state.allowedTools().contains(DefaultToolRegistry.STRUCTURED_QUERY)
        && structuredQueryBackend == null) {
      return ToolExecutionResult.failClosed("structured_query backend is not configured");
    }
    if (backend == null && state.allowedTools().stream().anyMatch(this::usesRetrievalBackend)) {
      return ToolExecutionResult.failClosed("no retrieval tool backend is configured");
    }

    final List<String> toolNames =
        state.allowedTools().stream().sorted(Comparator.naturalOrder()).toList();
    if (state.classification() == com.medassist.agent.state.QueryClassification.MIXED) {
      return executeMixed(state, toolNames);
    }
    return executeSequential(state, toolNames);
  }

  private ToolExecutionResult executeMixed(final AgentState state, final List<String> toolNames) {
    final List<String> parallelTools =
        toolNames.stream().filter(MIXED_SEARCH_TOOLS::contains).toList();
    final List<ToolCallOutcome> outcomes = new ArrayList<>();
    if (!parallelTools.isEmpty()) {
      outcomes.addAll(executeConcurrently(state, parallelTools));
    }
    final List<String> sequentialTools =
        toolNames.stream().filter(toolName -> !MIXED_SEARCH_TOOLS.contains(toolName)).toList();
    for (final String toolName : sequentialTools) {
      outcomes.add(executeWithTimeout(state, toolName, backendFor(toolName)));
    }
    return toResult(outcomes);
  }

  private ToolExecutionResult executeSequential(final AgentState state, final List<String> tools) {
    final List<ToolCallOutcome> outcomes = new ArrayList<>();
    for (final String toolName : tools) {
      outcomes.add(executeWithTimeout(state, toolName, backendFor(toolName)));
      if (!outcomes.getLast().succeeded()) {
        break;
      }
    }
    return toResult(outcomes);
  }

  private List<ToolCallOutcome> executeConcurrently(
      final AgentState state, final List<String> toolNames) {
    final List<PendingCall> pendingCalls =
        toolNames.stream()
            .map(
                toolName -> {
                  final ToolInvocationRequest request = requestFor(state, toolName);
                  final Instant startedAt = Instant.now();
                  final long deadlineNanos = System.nanoTime() + perCallTimeout.toNanos();
                  final CompletableFuture<ToolCallOutcome> future =
                      CompletableFuture.supplyAsync(
                          () ->
                              AgentThreadContext.with(
                                  request,
                                  () -> executeBackend(request, backendFor(toolName), startedAt)),
                          executor);
                  return new PendingCall(toolName, request, startedAt, deadlineNanos, future);
                })
            .toList();
    return pendingCalls.stream().map(this::await).toList();
  }

  private ToolCallOutcome executeWithTimeout(
      final AgentState state, final String toolName, final ToolBackend toolBackend) {
    final ToolInvocationRequest request = requestFor(state, toolName);
    final Instant startedAt = Instant.now();
    final long deadlineNanos = System.nanoTime() + perCallTimeout.toNanos();
    final CompletableFuture<ToolCallOutcome> future =
        CompletableFuture.supplyAsync(
            () ->
                AgentThreadContext.with(
                    request, () -> executeBackend(request, toolBackend, startedAt)),
            executor);
    return await(new PendingCall(toolName, request, startedAt, deadlineNanos, future));
  }

  private ToolCallOutcome await(final PendingCall pending) {
    try {
      final long remaining = Math.max(1L, pending.deadlineNanos() - System.nanoTime());
      return pending.future().get(remaining, TimeUnit.NANOSECONDS);
    } catch (final TimeoutException exception) {
      pending.future().cancel(true);
      return ToolCallOutcome.failure(
          pending.toolName(), pending.request(), pending.startedAt(), "TIMEOUT");
    } catch (final CancellationException exception) {
      return ToolCallOutcome.failure(
          pending.toolName(), pending.request(), pending.startedAt(), "CANCELLED");
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      pending.future().cancel(true);
      return ToolCallOutcome.failure(
          pending.toolName(), pending.request(), pending.startedAt(), "INTERRUPTED");
    } catch (final ExecutionException exception) {
      return ToolCallOutcome.failure(
          pending.toolName(), pending.request(), pending.startedAt(), "FAILED");
    }
  }

  private ToolCallOutcome executeBackend(
      final ToolInvocationRequest request, final ToolBackend toolBackend, final Instant startedAt) {
    if (!bulkhead.tryAcquire()) {
      return ToolCallOutcome.failure(request.toolName(), request, startedAt, "BULKHEAD_REJECTED");
    }
    try {
      final ToolBackendResult result =
          Objects.requireNonNull(toolBackend.execute(request), "backend result");
      if (!request.toolName().equals(result.toolName())) {
        throw new IllegalStateException("tool backend returned a mismatched tool result");
      }
      final SafeToolResultProjection projection = ToolResultProjector.project(result);
      return ToolCallOutcome.success(
          new ToolCallRecord(
              UUID.randomUUID().toString(),
              request.toolName(),
              "SUCCEEDED",
              sha256(request.queryHash()),
              sha256(safeProjectionHashInput(projection)),
              startedAt,
              Instant.now()),
          ToolResultProjector.candidateMetadata(result),
          projection,
          ToolResultProjector.runtimeSafetyEvidence(result));
    } catch (final RuntimeException exception) {
      return ToolCallOutcome.failure(request.toolName(), request, startedAt, "FAILED");
    } finally {
      bulkhead.release();
    }
  }

  private ToolExecutionResult toResult(final List<ToolCallOutcome> outcomes) {
    final List<ToolCallRecord> calls =
        outcomes.stream().map(ToolCallOutcome::toolCall).filter(Objects::nonNull).toList();
    final List<ChunkCandidateMetadata> candidates =
        outcomes.stream().flatMap(outcome -> outcome.candidates().stream()).toList();
    final List<SafeChunkProjection> safeChunks =
        outcomes.stream().flatMap(outcome -> outcome.projection().chunks().stream()).toList();
    final List<SafeAggregationColumn> aggregationColumns =
        outcomes.stream()
            .flatMap(outcome -> outcome.projection().aggregationColumns().stream())
            .toList();
    final SafeToolResultProjection projection =
        new SafeToolResultProjection(safeChunks, aggregationColumns);
    final RuntimeSafetyEvidence runtimeSafetyEvidence =
        new RuntimeSafetyEvidence(
            outcomes.stream()
                .flatMap(outcome -> outcome.runtimeSafetyEvidence().chunks().stream())
                .toList());
    final ToolCallOutcome failure =
        outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElse(null);
    if (failure != null) {
      final boolean hasUsableProjection =
          !projection.chunks().isEmpty() || !projection.aggregationColumns().isEmpty();
      final boolean hasUsableEvidence = !runtimeSafetyEvidence.chunks().isEmpty();
      if (hasUsableProjection || hasUsableEvidence) {
        return ToolExecutionResult.partialFailure(
            calls, candidates, failure.failureReason(), projection, runtimeSafetyEvidence);
      }
      return ToolExecutionResult.failClosed(
          calls, candidates, failure.failureReason(), projection, runtimeSafetyEvidence);
    }
    return new ToolExecutionResult(
        calls, candidates, ToolExecutionStatus.SUCCESS, "", projection, runtimeSafetyEvidence);
  }

  private ToolBackend backendFor(final String toolName) {
    return DefaultToolRegistry.STRUCTURED_QUERY.equals(toolName) ? structuredQueryBackend : backend;
  }

  private boolean usesRetrievalBackend(final String toolName) {
    return !DefaultToolRegistry.STRUCTURED_QUERY.equals(toolName);
  }

  private ToolInvocationRequest requestFor(final AgentState state, final String toolName) {
    return new ToolInvocationRequest(
        toolName,
        state.deidentifiedQuery(),
        state.queryHash(),
        state.role(),
        state.classification(),
        topKForRetry(state.retryCount()),
        state.retrievalFilters(),
        state.traceId(),
        state.requestId());
  }

  private static int topKForRetry(final int retryCount) {
    if (retryCount <= 0) {
      return DEFAULT_TOP_K;
    }
    final long expandedTopK = (long) DEFAULT_TOP_K + (long) retryCount * RETRY_TOP_K_INCREMENT;
    return (int) Math.min(expandedTopK, ToolInvocationRequest.MAX_TOP_K);
  }

  private static Duration requirePositive(final Duration value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static String safeProjectionHashInput(final SafeToolResultProjection projection) {
    return projection.chunks().toString() + projection.aggregationColumns();
  }

  private static String sha256(final String value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (final byte valueByte : bytes) {
        hex.append(String.format("%02x", valueByte));
      }
      return "sha256:" + hex;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record PendingCall(
      String toolName,
      ToolInvocationRequest request,
      Instant startedAt,
      long deadlineNanos,
      CompletableFuture<ToolCallOutcome> future) {}

  private record ToolCallOutcome(
      ToolCallRecord toolCall,
      List<ChunkCandidateMetadata> candidates,
      SafeToolResultProjection projection,
      RuntimeSafetyEvidence runtimeSafetyEvidence,
      String failureReason) {
    private ToolCallOutcome {
      candidates = List.copyOf(candidates);
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(runtimeSafetyEvidence, "runtimeSafetyEvidence");
    }

    static ToolCallOutcome success(
        final ToolCallRecord toolCall,
        final List<ChunkCandidateMetadata> candidates,
        final SafeToolResultProjection projection,
        final RuntimeSafetyEvidence runtimeSafetyEvidence) {
      return new ToolCallOutcome(toolCall, candidates, projection, runtimeSafetyEvidence, "");
    }

    static ToolCallOutcome failure(
        final String toolName,
        final ToolInvocationRequest request,
        final Instant startedAt,
        final String reason) {
      return new ToolCallOutcome(
          new ToolCallRecord(
              UUID.randomUUID().toString(),
              toolName,
              reason,
              sha256(request.queryHash()),
              sha256(reason),
              startedAt,
              Instant.now()),
          List.of(),
          SafeToolResultProjection.empty(),
          RuntimeSafetyEvidence.empty(),
          reason);
    }

    boolean succeeded() {
      return failureReason.isBlank();
    }
  }
}
