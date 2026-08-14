package com.medassist.agent.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.medassist.agent.api.dto.AgentRequest;
import com.medassist.agent.api.dto.AgentResponse;
import com.medassist.agent.api.generation.GenerationCreateRequest;
import com.medassist.agent.api.generation.GenerationEventResponse;
import com.medassist.agent.application.AgentEntryService;
import com.medassist.agent.state.AgentRetrievalFilters;
import com.medassist.agent.state.CitationSummary;
import com.medassist.common.context.AuthenticatedRequestContext;
import com.medassist.common.context.ExecutionContext;
import io.micrometer.tracing.Span;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Owns the bounded generation state machine and authenticated replay lifecycle. */
public final class GenerationSessionService {
  private static final Set<GenerationStatus> ACTIVE =
      Set.of(GenerationStatus.CREATED, GenerationStatus.RUNNING);
  private static final Set<GenerationStatus> RUNNING = Set.of(GenerationStatus.RUNNING);
  private static final int EVENT_OVERHEAD_BYTES = 256;

  private final AgentEntryService entryService;
  private final GenerationStore store;
  private final GenerationProperties properties;
  private final GenerationOutputApprover outputApprover;
  private final GenerationPolicyGuard policyGuard;
  private final GenerationMetrics metrics;
  private final GenerationTracing tracing;
  private final ExecutorService generationExecutor;
  private final ExecutorService streamExecutor;
  private final ScheduledExecutorService scheduler;
  private final ObjectMapper canonicalMapper;
  private final Clock clock;
  private final java.security.SecureRandom random = new java.security.SecureRandom();
  private final ConcurrentHashMap<String, RuntimeHandle> runtimes = new ConcurrentHashMap<>();

  public GenerationSessionService(
      final AgentEntryService entryService,
      final GenerationStore store,
      final GenerationProperties properties,
      final GenerationOutputApprover outputApprover,
      final GenerationPolicyGuard policyGuard,
      final GenerationMetrics metrics,
      final GenerationTracing tracing,
      final ExecutorService generationExecutor,
      final ExecutorService streamExecutor,
      final ScheduledExecutorService scheduler,
      final ObjectMapper objectMapper,
      final Clock clock) {
    this.entryService = entryService;
    this.store = store;
    this.properties = properties;
    this.outputApprover = outputApprover;
    this.policyGuard = policyGuard;
    this.metrics = metrics;
    this.tracing = tracing;
    this.generationExecutor = generationExecutor;
    this.streamExecutor = streamExecutor;
    this.scheduler = scheduler;
    this.canonicalMapper =
        objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.clock = clock;
  }

  public CreateResult create(
      final GenerationCreateRequest request, final ExecutionContext context) {
    return tracing.trace(
        "medassist.generation.create",
        Map.of("generation.operation", "create"),
        () -> createInternal(request, context));
  }

  private CreateResult createInternal(
      final GenerationCreateRequest request, final ExecutionContext context) {
    requireBoundContext(context);
    final String policyVersion = policyGuard.authorizeCreate(context);
    final Instant now = clock.instant();
    final GenerationSession candidate =
        new GenerationSession(
            newGenerationId(),
            context.subject(),
            context.roles(),
            policyVersion,
            requestHash(request),
            GenerationStatus.CREATED,
            now,
            now.plus(properties.ttl()),
            null);
    final GenerationStore.CreationResult creation =
        store.create(
            candidate,
            request.idempotencyKey(),
            properties.maxActivePerUser(),
            properties.maxActiveGlobal());
    policyGuard.authorize(creation.session(), context, "create");

    final boolean startWon =
        store.transition(
            creation.session().generationId(),
            Set.of(GenerationStatus.CREATED),
            GenerationStatus.RUNNING,
            null);
    if (startWon) {
      metrics.created(creation.session().generationId(), creation.session().createdAt());
      try {
        startGeneration(creation.session().generationId(), request, context);
      } catch (final RuntimeException exception) {
        failAndStop(creation.session().generationId(), "GENERATION_START_FAILED", true);
        throw exception;
      }
    }
    final GenerationSession current =
        store.find(creation.session().generationId()).orElse(creation.session());
    return new CreateResult(current, creation.created());
  }

  public GenerationSession status(final String generationId, final ExecutionContext context) {
    return requireAuthorized(generationId, context, "status");
  }

  public GenerationSession cancel(final String generationId, final ExecutionContext context) {
    final GenerationSession session = requireAuthorized(generationId, context, "cancel");
    if (session.status() == GenerationStatus.CANCELLED) {
      return session;
    }
    if (session.status().terminal()) {
      throw new GenerationException(
          GenerationException.Reason.TERMINAL_CONFLICT, "generation session is already terminal");
    }
    final Optional<GenerationEvent> cancelled =
        terminal(
            generationId,
            GenerationEventType.CANCELLED,
            GenerationStatus.CANCELLED,
            Map.of("code", "GENERATION_CANCELLED"));
    if (cancelled.isPresent()) {
      stopRuntime(generationId, true, GenerationStatus.CANCELLED);
    }
    return store.find(generationId).orElse(session);
  }

  public List<GenerationEvent> readAfter(
      final String generationId, final String lastEventId, final ExecutionContext context) {
    requireCursor(lastEventId);
    requireAuthorized(generationId, context, "resume");
    return store.readAfter(generationId, cursor(lastEventId), properties.replayBatchSize());
  }

  public SseEmitter stream(
      final String generationId, final String lastEventId, final ExecutionContext context) {
    final boolean resumed =
        lastEventId != null && !lastEventId.isBlank() && !"0-0".equals(lastEventId);
    final GenerationSession session;
    try {
      requireBoundContext(context);
      requireCursor(lastEventId);
      session = requireAuthorized(generationId, context, "resume");
    } catch (final GenerationException exception) {
      if (resumed) {
        metrics.resume(
            exception.reason() == GenerationException.Reason.EXPIRED ? "expired" : "rejected");
      }
      throw exception;
    } catch (final GenerationStoreException exception) {
      if (resumed) {
        metrics.resume("unavailable");
      }
      throw exception;
    }
    final long timeoutMillis =
        Math.max(1L, Duration.between(clock.instant(), session.expiresAt()).toMillis());
    final SseEmitter emitter = new SseEmitter(timeoutMillis);
    final AtomicBoolean closed = new AtomicBoolean();
    subscriberConnected(generationId);
    final Runnable disconnect =
        () -> {
          if (closed.compareAndSet(false, true)) {
            subscriberDisconnected(generationId);
          }
        };
    emitter.onCompletion(disconnect);
    emitter.onTimeout(disconnect);
    emitter.onError(ignored -> disconnect.run());
    final Instant subscribedAt = clock.instant();
    try {
      streamExecutor.execute(
          () ->
              replayLoop(
                  generationId, lastEventId, context, emitter, disconnect, resumed, subscribedAt));
    } catch (final RejectedExecutionException exception) {
      disconnect.run();
      if (resumed) {
        metrics.resume("unavailable");
      }
      throw new GenerationStoreException(
          GenerationStoreException.Reason.UNAVAILABLE,
          "generation stream executor is unavailable",
          exception);
    }
    if (resumed) {
      metrics.resume("success");
    }
    return emitter;
  }

  private void startGeneration(
      final String generationId,
      final GenerationCreateRequest request,
      final ExecutionContext context) {
    final GenerationEvent accepted =
        append(
            generationId,
            GenerationEventType.ACCEPTED,
            Map.of("status", GenerationStatus.RUNNING.name()),
            RUNNING);
    metrics.append(generationId, estimateBytes(accepted));
    final RuntimeHandle handle = new RuntimeHandle(clock.instant(), tracing.currentSpan());
    final GenerationWork work =
        new GenerationWork(generationId, request.query(), request.filters(), context);
    final FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              executeGeneration(work);
              return null;
            });
    handle.task.set(task);
    runtimes.put(generationId, handle);
    handle.maxDuration.set(
        scheduler.schedule(
            () -> failAndStop(generationId, "GENERATION_DURATION_LIMIT", false),
            properties.maxDuration().toMillis(),
            TimeUnit.MILLISECONDS));
    scheduleBackgroundDeadline(generationId, handle);
    try {
      generationExecutor.execute(task);
    } catch (final RejectedExecutionException exception) {
      failAndStop(generationId, "GENERATION_EXECUTOR_UNAVAILABLE", false);
      throw new GenerationStoreException(
          GenerationStoreException.Reason.UNAVAILABLE,
          "generation executor is unavailable",
          exception);
    }
  }

  private void executeGeneration(final GenerationWork work) {
    tracing.trace(
        "medassist.generation.execute",
        traceParent(work.generationId()),
        Map.of("generation.operation", "generate"),
        () -> {
          executeGenerationInternal(work);
          return null;
        });
  }

  private void executeGenerationInternal(final GenerationWork work) {
    try {
      final AgentResponse response =
          entryService.execute(
              new AgentRequest(work.rawQuery(), null, work.filters()), work.context());
      if (Thread.currentThread().isInterrupted() || !isRunning(work.generationId())) {
        return;
      }
      final int estimatedTokens = estimateTokens(response.answer());
      if (estimatedTokens > properties.maxTokens()) {
        failAndStop(work.generationId(), "GENERATION_TOKEN_LIMIT", false);
        return;
      }
      final int plannedEventCount = plannedEventCount(response);
      if (plannedEventCount > properties.maxEvents()) {
        failAndStop(work.generationId(), "GENERATION_EVENT_LIMIT", false);
        return;
      }
      if (preflightBytes(response.answer(), plannedEventCount) > properties.maxBufferedBytes()) {
        failAndStop(work.generationId(), "GENERATION_BYTE_LIMIT", false);
        return;
      }
      final List<String> chunks =
          outputApprover.approveAndChunk(response.answer(), work.rawQuery());
      final List<PendingEvent> pending = buildPendingEvents(response, chunks);
      if (pending.size() + 1 > properties.maxEvents() - 1) {
        failAndStop(work.generationId(), "GENERATION_EVENT_LIMIT", false);
        return;
      }
      if (plannedBytes(pending) > properties.maxBufferedBytes()) {
        failAndStop(work.generationId(), "GENERATION_BYTE_LIMIT", false);
        return;
      }
      for (final PendingEvent event : pending) {
        if (Thread.currentThread().isInterrupted() || !isRunning(work.generationId())) {
          return;
        }
        final GenerationEvent stored =
            append(work.generationId(), event.type(), event.payload(), RUNNING);
        metrics.append(work.generationId(), estimateBytes(stored));
      }
      final Optional<GenerationEvent> completed =
          terminal(
              work.generationId(),
              GenerationEventType.FINAL,
              GenerationStatus.COMPLETED,
              Map.of(
                  "status",
                  GenerationStatus.COMPLETED.name(),
                  "abstained",
                  response.abstained(),
                  "termination_reason",
                  response.terminationReason().name()));
      if (completed.isPresent()) {
        metrics.append(work.generationId(), estimateBytes(completed.orElseThrow()));
        stopRuntime(work.generationId(), false, GenerationStatus.COMPLETED);
      }
    } catch (final GenerationOutputApprover.OutputApprovalException exception) {
      failAndStop(work.generationId(), "CLIENT_OUTPUT_REJECTED", false);
    } catch (final GenerationStateConflictException exception) {
      // A cancellation or expiry won the terminal transition.
    } catch (final RuntimeException exception) {
      if (!Thread.currentThread().isInterrupted()) {
        failAndStop(work.generationId(), "GENERATION_FAILED", true);
      }
    }
  }

  private List<PendingEvent> buildPendingEvents(
      final AgentResponse response, final List<String> chunks) {
    final List<PendingEvent> events = new ArrayList<>();
    for (final String chunk : chunks) {
      events.add(new PendingEvent(GenerationEventType.DELTA, Map.of("text", chunk)));
    }
    final CitationSummary citations = response.citationSummary();
    events.add(
        new PendingEvent(
            GenerationEventType.CITATION,
            Map.of(
                "candidate_count",
                citations.candidateCount(),
                "valid_count",
                citations.validCount(),
                "sufficient_evidence",
                citations.sufficientEvidence())));
    if (response.abstained()) {
      events.add(
          new PendingEvent(
              GenerationEventType.DEGRADATION,
              Map.of("code", "AGENT_" + response.terminationReason().name())));
    }
    return List.copyOf(events);
  }

  private long plannedBytes(final List<PendingEvent> pending) {
    long bytes = EVENT_OVERHEAD_BYTES * 2L;
    for (final PendingEvent event : pending) {
      bytes += estimateBytes(event.payload());
    }
    return bytes;
  }

  private int plannedEventCount(final AgentResponse response) {
    final String answer = response.answer();
    final long codePoints = answer == null ? 0L : answer.codePoints().count();
    final long chunks =
        codePoints == 0L
            ? 0L
            : (codePoints + properties.maxChunkCharacters() - 1L) / properties.maxChunkCharacters();
    final long total = 1L + chunks + 1L + (response.abstained() ? 1L : 0L) + 1L;
    return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
  }

  private static long preflightBytes(final String answer, final int eventCount) {
    final long textBytes = answer == null ? 0L : answer.getBytes(StandardCharsets.UTF_8).length;
    return textBytes + (long) eventCount * EVENT_OVERHEAD_BYTES + 512L;
  }

  private GenerationSession requireAuthorized(
      final String generationId, final ExecutionContext context, final String action) {
    requireBoundContext(context);
    requireGenerationId(generationId);
    final GenerationSession session =
        store
            .find(generationId)
            .orElseThrow(
                () ->
                    new GenerationException(
                        GenerationException.Reason.NOT_FOUND, "generation session was not found"));
    policyGuard.authorize(session, context, action);
    if (!clock.instant().isBefore(session.expiresAt())
        || session.status() == GenerationStatus.EXPIRED) {
      if (!session.status().terminal()) {
        terminal(
            generationId,
            GenerationEventType.ERROR,
            GenerationStatus.EXPIRED,
            errorPayload("GENERATION_EXPIRED", false));
        stopRuntime(generationId, true, GenerationStatus.EXPIRED);
      }
      throw new GenerationException(
          GenerationException.Reason.EXPIRED, "generation session has expired");
    }
    return session;
  }

  private void replayLoop(
      final String generationId,
      final String lastEventId,
      final ExecutionContext context,
      final SseEmitter emitter,
      final Runnable disconnect,
      final boolean resumed,
      final Instant subscribedAt) {
    tracing.trace(
        "medassist.generation.replay",
        traceParent(generationId),
        Map.of("generation.operation", "replay", "generation.resumed", resumed),
        () -> {
          replayLoopInternal(
              generationId, lastEventId, context, emitter, disconnect, resumed, subscribedAt);
          return null;
        });
  }

  private void replayLoopInternal(
      final String generationId,
      final String lastEventId,
      final ExecutionContext context,
      final SseEmitter emitter,
      final Runnable disconnect,
      final boolean resumed,
      final Instant subscribedAt) {
    String current = cursor(lastEventId);
    boolean firstReplay = true;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        final GenerationSession session = requireAuthorized(generationId, context, "resume");
        final List<GenerationEvent> events =
            store.readAfter(generationId, current, properties.replayBatchSize());
        for (final GenerationEvent event : events) {
          if (compareIds(event.eventId(), current) <= 0) {
            metrics.duplicate();
            continue;
          }
          emitter.send(
              SseEmitter.event()
                  .id(event.eventId())
                  .name(event.type().wireName())
                  .data(GenerationEventResponse.from(event)));
          current = event.eventId();
          if (resumed && firstReplay) {
            metrics.recordRecoveryLatency(Duration.between(subscribedAt, clock.instant()));
            firstReplay = false;
          }
          if (event.terminal()) {
            emitter.complete();
            return;
          }
        }
        if (events.isEmpty() && session.status().terminal()) {
          emitter.complete();
          return;
        }
        Thread.sleep(properties.replayPollInterval().toMillis());
      }
    } catch (final IOException exception) {
      // The client disconnected; generation continues only within the configured window.
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (final RuntimeException exception) {
      emitter.completeWithError(exception);
    } finally {
      disconnect.run();
    }
  }

  private GenerationEvent append(
      final String generationId,
      final GenerationEventType type,
      final Map<String, Object> payload,
      final Set<GenerationStatus> expected) {
    return tracing.trace(
        "medassist.generation.event.append",
        traceParent(generationId),
        Map.of("generation.operation", "append", "generation.event_type", type.wireName()),
        () ->
            store.append(
                new GenerationEvent(
                    null,
                    generationId,
                    type,
                    GenerationEvent.CURRENT_SCHEMA_VERSION,
                    clock.instant(),
                    payload),
                expected,
                properties.maxEvents(),
                properties.maxBufferedBytes(),
                properties.ttl()));
  }

  private Optional<GenerationEvent> terminal(
      final String generationId,
      final GenerationEventType type,
      final GenerationStatus status,
      final Map<String, Object> payload) {
    return tracing.trace(
        "medassist.generation.event.append",
        traceParent(generationId),
        Map.of(
            "generation.operation", "append",
            "generation.event_type", type.wireName(),
            "generation.status", status.name()),
        () ->
            store.appendTerminal(
                new GenerationEvent(
                    null,
                    generationId,
                    type,
                    GenerationEvent.CURRENT_SCHEMA_VERSION,
                    clock.instant(),
                    payload),
                ACTIVE,
                status,
                properties.maxEvents(),
                properties.maxBufferedBytes(),
                properties.ttl()));
  }

  private void failAndStop(final String generationId, final String code, final boolean retryable) {
    try {
      final Optional<GenerationEvent> failed =
          terminal(
              generationId,
              GenerationEventType.ERROR,
              GenerationStatus.FAILED,
              errorPayload(code, retryable));
      if (failed.isPresent()) {
        metrics.append(generationId, estimateBytes(failed.orElseThrow()));
        stopRuntime(generationId, true, GenerationStatus.FAILED);
      }
    } catch (final GenerationStoreException exception) {
      stopRuntime(generationId, true, GenerationStatus.FAILED);
    }
  }

  private void subscriberConnected(final String generationId) {
    final RuntimeHandle handle = runtimes.get(generationId);
    if (handle == null) {
      return;
    }
    handle.subscribers.incrementAndGet();
    final ScheduledFuture<?> deadline = handle.backgroundDeadline.getAndSet(null);
    if (deadline != null) {
      final Duration background = Duration.between(handle.backgroundSince.get(), clock.instant());
      if (!background.isNegative()) {
        metrics.recordBackgroundDuration(background);
      }
      deadline.cancel(false);
    }
  }

  private void subscriberDisconnected(final String generationId) {
    final RuntimeHandle handle = runtimes.get(generationId);
    if (handle != null && handle.subscribers.updateAndGet(value -> Math.max(0, value - 1)) == 0) {
      scheduleBackgroundDeadline(generationId, handle);
    }
  }

  private void scheduleBackgroundDeadline(final String generationId, final RuntimeHandle handle) {
    if (handle.subscribers.get() != 0 || handle.finished.get()) {
      return;
    }
    final ScheduledFuture<?> existing = handle.backgroundDeadline.get();
    if (existing != null && !existing.isDone()) {
      return;
    }
    handle.backgroundSince.set(clock.instant());
    final ScheduledFuture<?> deadline =
        scheduler.schedule(
            () -> failAndStop(generationId, "BACKGROUND_WINDOW_EXCEEDED", false),
            properties.maxBackgroundWindow().toMillis(),
            TimeUnit.MILLISECONDS);
    handle.backgroundDeadline.set(deadline);
  }

  private void stopRuntime(
      final String generationId, final boolean interrupt, final GenerationStatus terminalStatus) {
    final RuntimeHandle handle = runtimes.remove(generationId);
    if (handle != null && handle.finished.compareAndSet(false, true)) {
      cancelScheduled(handle.maxDuration.get());
      cancelScheduled(handle.backgroundDeadline.get());
      if (interrupt) {
        final FutureTask<Void> task = handle.task.get();
        if (task != null) {
          task.cancel(true);
        }
      }
    }
    metrics.terminal(generationId, terminalStatus, clock.instant());
  }

  private boolean isRunning(final String generationId) {
    return store
        .find(generationId)
        .map(session -> session.status() == GenerationStatus.RUNNING)
        .orElse(false);
  }

  private String requestHash(final GenerationCreateRequest request) {
    try {
      final Map<String, Object> canonical = new TreeMap<>();
      canonical.put("filters", request.filters());
      canonical.put("query", request.query());
      return hash(canonicalMapper.writeValueAsBytes(canonical));
    } catch (final JsonProcessingException exception) {
      throw new GenerationException(
          GenerationException.Reason.INVALID_REQUEST, "generation request is invalid");
    }
  }

  private long estimateBytes(final GenerationEvent event) {
    return estimateBytes(event.payload());
  }

  private long estimateBytes(final Map<String, Object> payload) {
    try {
      return canonicalMapper.writeValueAsBytes(payload).length + EVENT_OVERHEAD_BYTES;
    } catch (final JsonProcessingException exception) {
      throw new IllegalArgumentException("generation event payload is invalid", exception);
    }
  }

  /** Conservative hard-limit estimate: every Unicode code point consumes at least one token. */
  static int estimateTokens(final String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    return Math.toIntExact(value.codePoints().count());
  }

  private Span traceParent(final String generationId) {
    final Span current = tracing.currentSpan();
    if (current != null) {
      return current;
    }
    final RuntimeHandle handle = runtimes.get(generationId);
    return handle == null ? null : handle.parentSpan;
  }

  private String newGenerationId() {
    final byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static Map<String, Object> errorPayload(final String code, final boolean retryable) {
    return Map.of("code", code, "retryable", retryable);
  }

  private static void requireBoundContext(final ExecutionContext context) {
    final ExecutionContext bound = AuthenticatedRequestContext.requireCurrent();
    if (!bound.equals(context)) {
      throw new GenerationException(
          GenerationException.Reason.FORBIDDEN, "authenticated execution context changed");
    }
  }

  private static void requireGenerationId(final String generationId) {
    if (generationId == null || !generationId.matches("[A-Za-z0-9_-]{22,128}")) {
      throw new GenerationException(
          GenerationException.Reason.INVALID_REQUEST, "generation_id is invalid");
    }
  }

  private static void requireCursor(final String eventId) {
    if (eventId == null || eventId.isBlank()) {
      return;
    }
    final String[] parts = eventId.split("-", -1);
    if (parts.length != 2 || !isLongComponent(parts[0]) || !isLongComponent(parts[1])) {
      throw new GenerationException(
          GenerationException.Reason.INVALID_REQUEST, "Last-Event-ID is invalid");
    }
  }

  private static boolean isLongComponent(final String value) {
    if (value.isEmpty() || value.length() > 19 || !value.chars().allMatch(Character::isDigit)) {
      return false;
    }
    try {
      Long.parseLong(value);
      return true;
    } catch (final NumberFormatException exception) {
      return false;
    }
  }

  private static String cursor(final String eventId) {
    return eventId == null || eventId.isBlank() ? "0-0" : eventId;
  }

  private static int compareIds(final String left, final String right) {
    final String[] leftParts = left.split("-", 2);
    final String[] rightParts = right.split("-", 2);
    final int primary = Long.compare(Long.parseLong(leftParts[0]), Long.parseLong(rightParts[0]));
    return primary != 0
        ? primary
        : Long.compare(Long.parseLong(leftParts[1]), Long.parseLong(rightParts[1]));
  }

  private static String hash(final byte[] value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void cancelScheduled(final ScheduledFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }

  public record CreateResult(GenerationSession session, boolean created) {}

  private record PendingEvent(GenerationEventType type, Map<String, Object> payload) {}

  private record GenerationWork(
      String generationId,
      String rawQuery,
      AgentRetrievalFilters filters,
      ExecutionContext context) {
    @Override
    public String toString() {
      return "GenerationWork[generationId="
          + generationId
          + ", rawQuery=<redacted>, filters=<redacted>]";
    }
  }

  private static final class RuntimeHandle {
    private final AtomicReference<FutureTask<Void>> task = new AtomicReference<>();
    private final AtomicInteger subscribers = new AtomicInteger();
    private final AtomicReference<ScheduledFuture<?>> maxDuration = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> backgroundDeadline = new AtomicReference<>();
    private final AtomicReference<Instant> backgroundSince;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Span parentSpan;

    private RuntimeHandle(final Instant createdAt, final Span parentSpan) {
      this.backgroundSince = new AtomicReference<>(createdAt);
      this.parentSpan = parentSpan;
    }
  }
}
