package com.medassist.agent.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.api.dto.AgentRequest;
import com.medassist.agent.api.dto.AgentResponse;
import com.medassist.agent.api.generation.GenerationCreateRequest;
import com.medassist.agent.application.AgentEntryService;
import com.medassist.agent.state.AgentRetrievalFilters;
import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.TerminationReason;
import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import com.medassist.common.context.ExecutorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GenerationSessionServiceTest {
  private static final String IDEMPOTENCY_KEY = "idem-key-1234567";
  private final AgentEntryService entryService = mock(AgentEntryService.class);
  private final InMemoryGenerationStore store = new InMemoryGenerationStore();
  private final ExecutorService generationExecutor =
      ExecutorFactory.newVirtualThreadPerTaskExecutor();
  private final ExecutorService streamExecutor = ExecutorFactory.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void shutdown() {
    generationExecutor.shutdownNow();
    streamExecutor.shutdownNow();
    scheduler.shutdownNow();
    ContextCarrier.clear();
  }

  @Test
  void sameIdempotencyKeyStartsOnlyOneGenerationAndConflictsOnDifferentRequest() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenAnswer(
            ignored -> {
              calls.incrementAndGet();
              entered.countDown();
              release.await();
              return response("approved answer");
            });
    final GenerationSessionService service = service(defaults());
    final ExecutionContext context = context("user-1", "clinician");
    final GenerationCreateRequest request = request("private question", IDEMPOTENCY_KEY);

    final GenerationSessionService.CreateResult firstResult =
        bound(context, () -> service.create(request, context));
    final GenerationSession first = firstResult.session();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    final GenerationSessionService.CreateResult secondResult =
        bound(context, () -> service.create(request, context));
    final GenerationSession second = secondResult.session();

    assertTrue(firstResult.created());
    assertFalse(secondResult.created());
    assertEquals(first.generationId(), second.generationId());
    assertEquals(1, calls.get());
    assertThrows(
        GenerationStoreException.class,
        () ->
            bound(
                context,
                () -> service.create(request("other question", IDEMPOTENCY_KEY), context)));
    release.countDown();
    awaitStatus(service, first.generationId(), context, GenerationStatus.COMPLETED);
    final ArgumentCaptor<AgentRequest> captured = ArgumentCaptor.forClass(AgentRequest.class);
    verify(entryService).execute(captured.capture(), any(ExecutionContext.class));
    assertEquals(request.filters(), captured.getValue().retrievalFilters());
  }

  @Test
  void resumesAfterMoreThanThreeEventsWithoutDuplicatesAndReplaysTerminal() {
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response("abcdefghijklmnop"));
    final GenerationSessionService service = service(withChunkSize(defaults(), 3));
    final ExecutionContext context = context("user-1", "clinician");
    final GenerationSession session =
        bound(
            context, () -> service.create(request("question", IDEMPOTENCY_KEY), context).session());
    awaitStatus(service, session.generationId(), context, GenerationStatus.COMPLETED);

    final List<GenerationEvent> all =
        bound(context, () -> service.readAfter(session.generationId(), null, context));
    assertTrue(all.size() > 3);
    final String cursor = all.get(2).eventId();
    final List<GenerationEvent> resumed =
        bound(context, () -> service.readAfter(session.generationId(), cursor, context));
    final Set<String> prefixIds =
        new HashSet<>(all.subList(0, 3).stream().map(GenerationEvent::eventId).toList());

    assertEquals(all.subList(3, all.size()), resumed);
    assertTrue(resumed.stream().noneMatch(event -> prefixIds.contains(event.eventId())));
    assertTrue(resumed.get(resumed.size() - 1).terminal());
    final List<GenerationEvent> replayed =
        bound(context, () -> service.readAfter(session.generationId(), "0-0", context));
    assertEquals(all, replayed);
  }

  @Test
  void rejectsOverflowingLastEventIdBeforeStoreAccess() {
    final GenerationSessionService service = service(defaults());
    final ExecutionContext context = context("user-1", "clinician");

    assertThrows(
        GenerationException.class,
        () ->
            bound(
                context,
                () ->
                    service.readAfter(
                        "valid-generation-id-1234567890", "999999999999999999999-0", context)));
  }

  @Test
  void deniesAnotherOwnerChangedRoleAndChangedPolicy() {
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response("answer"));
    final GenerationSessionService service = service(defaults());
    final ExecutionContext owner = context("owner", "clinician");
    final GenerationSession session =
        bound(owner, () -> service.create(request("question", IDEMPOTENCY_KEY), owner).session());

    assertForbidden(service, session, context("other", "clinician"));
    assertForbidden(service, session, context("owner", "researcher"));
    final ExecutionContext changedPolicy =
        new ExecutionContext(
            "owner", Set.of("clinician"), "request", "trace", Map.of("policy_version", "v2"));
    assertForbidden(service, session, changedPolicy);
  }

  @Test
  void explicitCancellationInterruptsFutureAndAppendsOneReplayableTerminal() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicBoolean interrupted = new AtomicBoolean();
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              try {
                Thread.sleep(10_000);
              } catch (final InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
              }
              return response("late answer");
            });
    final GenerationSessionService service = service(defaults());
    final ExecutionContext context = context("user-1", "clinician");
    final GenerationSession session =
        bound(
            context, () -> service.create(request("question", IDEMPOTENCY_KEY), context).session());
    assertTrue(entered.await(2, TimeUnit.SECONDS));

    final GenerationSession cancelled =
        bound(context, () -> service.cancel(session.generationId(), context));
    bound(context, () -> service.cancel(session.generationId(), context));
    final List<GenerationEvent> events =
        bound(context, () -> service.readAfter(session.generationId(), null, context));

    assertEquals(GenerationStatus.CANCELLED, cancelled.status());
    assertTrue(await(interrupted::get, Duration.ofSeconds(2)));
    assertEquals(1, events.stream().filter(GenerationEvent::terminal).count());
    assertEquals(GenerationEventType.CANCELLED, events.get(events.size() - 1).type());
  }

  @Test
  void enforcesTokenEventByteAndActiveSessionLimits() throws Exception {
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response("x".repeat(5000)));
    final ExecutionContext firstUser = context("user-1", "clinician");

    assertLimitCode(withMaxTokens(defaults(), 1), firstUser, "GENERATION_TOKEN_LIMIT");
    assertLimitCode(withMaxEvents(defaults(), 3), firstUser, "GENERATION_EVENT_LIMIT");
    assertLimitCode(withMaxBytes(defaults(), 1024), firstUser, "GENERATION_BYTE_LIMIT");

    final CountDownLatch release = new CountDownLatch(1);
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenAnswer(
            ignored -> {
              release.await();
              return response("answer");
            });
    final GenerationProperties activeLimits = withActiveLimits(defaults(), 1, 1);
    final GenerationSessionService service = service(activeLimits);
    bound(firstUser, () -> service.create(request("one", "idem-key-0000001"), firstUser));
    assertEquals(
        GenerationStoreException.Reason.ACTIVE_LIMIT,
        assertThrows(
                GenerationStoreException.class,
                () ->
                    bound(
                        firstUser,
                        () -> service.create(request("two", "idem-key-0000002"), firstUser)))
            .reason());
    final ExecutionContext secondUser = context("user-2", "clinician");
    assertEquals(
        GenerationStoreException.Reason.ACTIVE_LIMIT,
        assertThrows(
                GenerationStoreException.class,
                () ->
                    bound(
                        secondUser,
                        () -> service.create(request("three", "idem-key-0000003"), secondUser)))
            .reason());
    release.countDown();
  }

  @Test
  void enforcesDurationBackgroundWindowAndTtl() {
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenAnswer(
            ignored -> {
              Thread.sleep(10_000);
              return response("answer");
            });
    final ExecutionContext context = context("user-1", "clinician");

    final GenerationProperties durationLimits =
        withWindows(
            defaults(), Duration.ofMillis(40), Duration.ofMillis(200), Duration.ofMillis(400));
    final GenerationSessionService durationService = service(durationLimits);
    final GenerationSession durationSession =
        bound(
            context,
            () ->
                durationService
                    .create(request("question", "idem-duration-001"), context)
                    .session());
    awaitStatus(durationService, durationSession.generationId(), context, GenerationStatus.FAILED);
    assertTerminalCode(
        durationService, durationSession.generationId(), context, "GENERATION_DURATION_LIMIT");

    final GenerationProperties backgroundLimits =
        withWindows(
            defaults(), Duration.ofMillis(200), Duration.ofMillis(40), Duration.ofMillis(400));
    final GenerationSessionService backgroundService = service(backgroundLimits);
    final GenerationSession backgroundSession =
        bound(
            context,
            () ->
                backgroundService
                    .create(request("question", "idem-background01"), context)
                    .session());
    awaitStatus(
        backgroundService, backgroundSession.generationId(), context, GenerationStatus.FAILED);
    assertTerminalCode(
        backgroundService, backgroundSession.generationId(), context, "BACKGROUND_WINDOW_EXCEEDED");

    final GenerationProperties ttlLimits =
        withWindows(
            defaults(), Duration.ofMillis(40), Duration.ofMillis(40), Duration.ofMillis(100));
    final GenerationSessionService ttlService = service(ttlLimits);
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response("answer"));
    final GenerationSession ttlSession =
        bound(
            context,
            () -> ttlService.create(request("question", "idem-ttl-00000001"), context).session());
    awaitStatus(ttlService, ttlSession.generationId(), context, GenerationStatus.COMPLETED);
    assertTrue(
        await(
            () -> Clock.systemUTC().instant().isAfter(ttlSession.expiresAt()),
            Duration.ofSeconds(1)));
    assertEquals(
        GenerationException.Reason.EXPIRED,
        assertThrows(
                GenerationException.class,
                () -> bound(context, () -> ttlService.status(ttlSession.generationId(), context)))
            .reason());
  }

  @Test
  void rawQueryIsAbsentFromStoredEventsAndLoggableRepresentations() {
    final String rawQuery = "RAW-PATIENT-QUERY-9384";
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response("answer repeats " + rawQuery));
    final GenerationSessionService service = service(defaults());
    final ExecutionContext context = context("user-1", "clinician");
    final GenerationCreateRequest request = request(rawQuery, IDEMPOTENCY_KEY);
    final GenerationSession session =
        bound(context, () -> service.create(request, context).session());
    awaitStatus(service, session.generationId(), context, GenerationStatus.FAILED);
    final List<GenerationEvent> events =
        bound(context, () -> service.readAfter(session.generationId(), null, context));

    assertFalse(request.toString().contains(rawQuery));
    assertTrue(events.stream().noneMatch(event -> event.payload().toString().contains(rawQuery)));
    assertTrue(events.stream().noneMatch(event -> event.toString().contains(rawQuery)));
    assertTerminalCode(service, session.generationId(), context, "CLIENT_OUTPUT_REJECTED");
  }

  @Test
  void sensitiveOutputIsRejectedWithoutPersistingContentOrFindings() {
    final String sensitiveAnswer = "patient@example.org";
    when(entryService.execute(any(AgentRequest.class), any(ExecutionContext.class)))
        .thenReturn(response(sensitiveAnswer));
    final GenerationSessionService service = service(defaults());
    final ExecutionContext context = context("user-1", "clinician");
    final GenerationSession session =
        bound(
            context, () -> service.create(request("question", IDEMPOTENCY_KEY), context).session());

    awaitStatus(service, session.generationId(), context, GenerationStatus.FAILED);
    final List<GenerationEvent> events =
        bound(context, () -> service.readAfter(session.generationId(), null, context));

    assertTrue(events.stream().noneMatch(event -> event.toString().contains(sensitiveAnswer)));
    assertTrue(events.stream().noneMatch(event -> event.toString().contains("EMAIL")));
    assertTerminalCode(service, session.generationId(), context, "CLIENT_OUTPUT_REJECTED");
  }

  @Test
  void tokenEstimateIsConservativeForCjkAndSupplementaryCodePoints() {
    assertEquals(4, GenerationSessionService.estimateTokens("\u60a3\u8005\ud83e\udd94A"));
  }

  private void assertLimitCode(
      final GenerationProperties limits,
      final ExecutionContext context,
      final String expectedCode) {
    final GenerationSessionService service = service(limits);
    final String idempotency = "idem-" + expectedCode.toLowerCase(java.util.Locale.ROOT);
    final GenerationSession session =
        bound(context, () -> service.create(request("question", idempotency), context).session());
    awaitStatus(service, session.generationId(), context, GenerationStatus.FAILED);
    assertTerminalCode(service, session.generationId(), context, expectedCode);
  }

  private void assertTerminalCode(
      final GenerationSessionService service,
      final String generationId,
      final ExecutionContext context,
      final String expectedCode) {
    final List<GenerationEvent> events =
        bound(context, () -> service.readAfter(generationId, null, context));
    assertEquals(expectedCode, events.get(events.size() - 1).payload().get("code"));
  }

  private void assertForbidden(
      final GenerationSessionService service,
      final GenerationSession session,
      final ExecutionContext context) {
    assertEquals(
        GenerationException.Reason.FORBIDDEN,
        assertThrows(
                GenerationException.class,
                () -> bound(context, () -> service.status(session.generationId(), context)))
            .reason());
  }

  private GenerationSessionService service(final GenerationProperties properties) {
    return new GenerationSessionService(
        entryService,
        store,
        properties,
        new GenerationOutputApprover(properties.maxChunkCharacters()),
        new GenerationPolicyGuard(properties.policyVersion()),
        new GenerationMetrics(new SimpleMeterRegistry()),
        new GenerationTracing(null),
        generationExecutor,
        streamExecutor,
        scheduler,
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC());
  }

  private static GenerationProperties defaults() {
    return new GenerationProperties(
        "test",
        "v1",
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        10_000,
        256,
        100_000,
        2,
        10,
        Duration.ofSeconds(10),
        Duration.ofSeconds(1),
        512,
        100,
        Duration.ofMillis(5));
  }

  private static GenerationProperties withChunkSize(
      final GenerationProperties value, final int maxChunkCharacters) {
    return copy(
        value,
        value.maxDuration(),
        value.maxBackgroundWindow(),
        value.maxTokens(),
        value.maxEvents(),
        value.maxBufferedBytes(),
        value.maxActivePerUser(),
        value.maxActiveGlobal(),
        value.ttl(),
        maxChunkCharacters);
  }

  private static GenerationProperties withMaxTokens(
      final GenerationProperties value, final int maxTokens) {
    return copy(
        value,
        value.maxDuration(),
        value.maxBackgroundWindow(),
        maxTokens,
        value.maxEvents(),
        value.maxBufferedBytes(),
        value.maxActivePerUser(),
        value.maxActiveGlobal(),
        value.ttl(),
        value.maxChunkCharacters());
  }

  private static GenerationProperties withMaxEvents(
      final GenerationProperties value, final int maxEvents) {
    return copy(
        value,
        value.maxDuration(),
        value.maxBackgroundWindow(),
        value.maxTokens(),
        maxEvents,
        value.maxBufferedBytes(),
        value.maxActivePerUser(),
        value.maxActiveGlobal(),
        value.ttl(),
        value.maxChunkCharacters());
  }

  private static GenerationProperties withMaxBytes(
      final GenerationProperties value, final long maxBytes) {
    return copy(
        value,
        value.maxDuration(),
        value.maxBackgroundWindow(),
        value.maxTokens(),
        value.maxEvents(),
        maxBytes,
        value.maxActivePerUser(),
        value.maxActiveGlobal(),
        value.ttl(),
        value.maxChunkCharacters());
  }

  private static GenerationProperties withActiveLimits(
      final GenerationProperties value, final int user, final int global) {
    return copy(
        value,
        value.maxDuration(),
        value.maxBackgroundWindow(),
        value.maxTokens(),
        value.maxEvents(),
        value.maxBufferedBytes(),
        user,
        global,
        value.ttl(),
        value.maxChunkCharacters());
  }

  private static GenerationProperties withWindows(
      final GenerationProperties value,
      final Duration duration,
      final Duration background,
      final Duration ttl) {
    return copy(
        value,
        duration,
        background,
        value.maxTokens(),
        value.maxEvents(),
        value.maxBufferedBytes(),
        value.maxActivePerUser(),
        value.maxActiveGlobal(),
        ttl,
        value.maxChunkCharacters());
  }

  private static GenerationProperties copy(
      final GenerationProperties value,
      final Duration duration,
      final Duration background,
      final int tokens,
      final int events,
      final long bytes,
      final int user,
      final int global,
      final Duration ttl,
      final int chunkCharacters) {
    return new GenerationProperties(
        value.keyPrefix(),
        value.policyVersion(),
        duration,
        background,
        tokens,
        events,
        bytes,
        user,
        global,
        ttl,
        value.expiredMetadataRetention(),
        chunkCharacters,
        value.replayBatchSize(),
        value.replayPollInterval());
  }

  private static GenerationCreateRequest request(final String query, final String idempotencyKey) {
    return new GenerationCreateRequest(
        query,
        new AgentRetrievalFilters(Set.of("GUIDELINE"), Set.of("WHO"), null, null, Set.of()),
        idempotencyKey);
  }

  private static AgentResponse response(final String answer) {
    return new AgentResponse(
        "trace",
        "request",
        answer,
        false,
        "",
        "hash",
        null,
        new CitationSummary(2, 2, true),
        TerminationReason.COMPLETED);
  }

  private static ExecutionContext context(final String subject, final String role) {
    return new ExecutionContext(subject, Set.of(role), "request", "trace", Map.of());
  }

  private static <T> T bound(final ExecutionContext context, final Supplier<T> action) {
    ContextCarrier.restore(context);
    try {
      return action.get();
    } finally {
      ContextCarrier.clear();
    }
  }

  private static void awaitStatus(
      final GenerationSessionService service,
      final String generationId,
      final ExecutionContext context,
      final GenerationStatus expected) {
    assertTrue(
        await(
            () -> bound(context, () -> service.status(generationId, context)).status() == expected,
            Duration.ofSeconds(3)));
  }

  private static boolean await(final Supplier<Boolean> condition, final Duration timeout) {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.get()) {
        return true;
      }
      try {
        Thread.sleep(5);
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.get();
  }
}
