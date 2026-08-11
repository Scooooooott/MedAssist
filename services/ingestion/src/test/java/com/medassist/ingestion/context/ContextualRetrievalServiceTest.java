package com.medassist.ingestion.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.Chunk;
import com.medassist.domain.SourceRange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextualRetrievalServiceTest {
  private static final UUID DOCUMENT_VERSION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String ORIGINAL_TEXT = "The original chunk text stays unchanged.";
  private static final String CONTEXT_ONLY_MARKER = "CTX_ONLY_M2_3_BOUNDARY_MARKER";

  @Test
  void offModeDoesNotCallProviderOrAddContext() {
    final AtomicInteger calls = new AtomicInteger();
    final ContextualRetrievalService service =
        service(
            new InMemoryContextCache(),
            request -> {
              calls.incrementAndGet();
              return new ContextLlmResponse("should not be used");
            },
            approvedGate());

    final ContextualChunk result =
        service.prepare(request(ContextualRetrievalMode.OFF)).chunks().get(0);

    assertEquals(ContextStatus.OFF, result.status());
    assertEquals("", result.contextPrefix());
    assertEquals(ORIGINAL_TEXT, result.chunk().text());
    assertEquals(0, calls.get());
  }

  @Test
  void ruleModeUsesTitlePublisherBreadcrumbAndOneSharedSummary() {
    final AtomicInteger calls = new AtomicInteger();
    final ContextualRetrievalService service =
        service(
            new InMemoryContextCache(),
            request -> {
              calls.incrementAndGet();
              return new ContextLlmResponse("unused");
            },
            approvedGate());

    final ContextualChunk result =
        service.prepare(request(ContextualRetrievalMode.RULE_BASED)).chunks().get(0);

    assertEquals(ContextStatus.RULE_BASED, result.status());
    assertEquals(
        "Document title: Clinical Guide\n"
            + "Publisher: Example Health\n"
            + "Document summary: Shared summary\n"
            + "Breadcrumb: Clinical Guide > Treatment",
        result.contextPrefix());
    assertEquals(0, calls.get());
    assertEquals(ORIGINAL_TEXT, result.chunk().text());
  }

  @Test
  void repeatedRuleCallsUseModeAndPromptVersionedCache() {
    final InMemoryContextCache cache = new InMemoryContextCache();
    final ContextualRetrievalService service =
        service(
            cache,
            request -> {
              throw new AssertionError("rule mode must not call LLM");
            },
            approvedGate());

    final ContextualChunk first =
        service.prepare(request(ContextualRetrievalMode.RULE_BASED)).chunks().get(0);
    final ContextualChunk second =
        service.prepare(request(ContextualRetrievalMode.RULE_BASED)).chunks().get(0);

    assertEquals(ContextStatus.RULE_BASED, first.status());
    assertEquals(ContextStatus.RULE_BASED_CACHE_HIT, second.status());
    assertEquals(first.contextPrefix(), second.contextPrefix());
    assertTrue(
        cache
            .get(
                new ContextCacheKey(
                    DOCUMENT_VERSION_ID,
                    "structure-v1",
                    0,
                    ContextualRetrievalMode.RULE_BASED,
                    "prompt-v1"))
            .isPresent());
    assertFalse(
        cache
            .get(
                new ContextCacheKey(
                    DOCUMENT_VERSION_ID,
                    "structure-v1",
                    0,
                    ContextualRetrievalMode.LLM_GENERATED,
                    "prompt-v1"))
            .isPresent());
  }

  @Test
  void llmModePassesSharedPrefixSeparatelyAndCachesResponse(@TempDir final Path tempDir)
      throws Exception {
    final Path artifact = writeArtifact(tempDir, "{\"within_budget\":true,\"estimated_usd\":1.25}");
    final AtomicInteger calls = new AtomicInteger();
    final ContextLlmRequest[] captured = new ContextLlmRequest[1];
    final ContextualRetrievalService service =
        service(
            new InMemoryContextCache(),
            request -> {
              calls.incrementAndGet();
              captured[0] = request;
              return new ContextLlmResponse("LLM context");
            },
            new ApprovedCostGate(artifact, sha256(Files.readAllBytes(artifact))));

    final ContextualChunk first =
        service.prepare(request(ContextualRetrievalMode.LLM_GENERATED)).chunks().get(0);
    final ContextualChunk second =
        service.prepare(request(ContextualRetrievalMode.LLM_GENERATED)).chunks().get(0);

    assertEquals(ContextStatus.LLM_GENERATED, first.status());
    assertEquals(ContextStatus.LLM_GENERATED_CACHE_HIT, second.status());
    assertEquals(1, calls.get());
    assertNotNull(captured[0]);
    assertEquals(
        "Document title: Clinical Guide\n"
            + "Publisher: Example Health\n"
            + "Document summary: Shared summary",
        captured[0].sharedDocumentPromptPrefix());
    assertEquals(ORIGINAL_TEXT, captured[0].originalChunkText());
    assertEquals(ORIGINAL_TEXT, first.chunk().text());
  }

  @Test
  void missingHashMismatchAndOverBudgetRejectBeforeProviderCall(@TempDir final Path tempDir)
      throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    final ContextLlmClient client =
        request -> {
          calls.incrementAndGet();
          return new ContextLlmResponse("must not be called");
        };
    final ContextualRetrievalRequest llmRequest = request(ContextualRetrievalMode.LLM_GENERATED);

    final ContextualRetrievalService missing =
        service(
            new InMemoryContextCache(),
            client,
            new ApprovedCostGate(tempDir.resolve("missing.json"), hashOf("missing")));
    assertThrows(ContextCostGateException.class, () -> missing.prepare(llmRequest));

    final Path mismatchArtifact = writeArtifact(tempDir, "{\"within_budget\":true}");
    final ContextualRetrievalService mismatch =
        service(
            new InMemoryContextCache(),
            client,
            new ApprovedCostGate(mismatchArtifact, hashOf("wrong")));
    assertThrows(ContextCostGateException.class, () -> mismatch.prepare(llmRequest));

    final Path overBudgetArtifact = writeArtifact(tempDir, "{\"within_budget\":false}");
    final ContextualRetrievalService overBudget =
        service(
            new InMemoryContextCache(),
            client,
            new ApprovedCostGate(
                overBudgetArtifact, sha256(Files.readAllBytes(overBudgetArtifact))));
    assertThrows(ContextCostGateException.class, () -> overBudget.prepare(llmRequest));
    assertEquals(0, calls.get());
  }

  @Test
  void llmFailureFallsBackToRuleContextAndMarksStatus(@TempDir final Path tempDir)
      throws Exception {
    final Path artifact = writeArtifact(tempDir, "{\"within_budget\":true}");
    final AtomicInteger calls = new AtomicInteger();
    final ContextualRetrievalService service =
        service(
            new InMemoryContextCache(),
            request -> {
              calls.incrementAndGet();
              throw new IllegalStateException("provider unavailable");
            },
            new ApprovedCostGate(artifact, sha256(Files.readAllBytes(artifact))));

    final ContextualChunk first =
        service.prepare(request(ContextualRetrievalMode.LLM_GENERATED)).chunks().get(0);
    final ContextualChunk second =
        service.prepare(request(ContextualRetrievalMode.LLM_GENERATED)).chunks().get(0);

    assertEquals(ContextStatus.LLM_FALLBACK_RULE_BASED, first.status());
    assertEquals(ContextStatus.LLM_FALLBACK_RULE_BASED_CACHE_HIT, second.status());
    assertTrue(first.contextPrefix().contains("Breadcrumb: Clinical Guide > Treatment"));
    assertEquals(ORIGINAL_TEXT, first.generationText());
    assertEquals(1, calls.get());
  }

  @Test
  void sixConsumerBoundariesKeepOriginalTextExceptEmbedding() {
    final ContextualChunk chunk =
        new ContextualChunk(
            chunk(),
            "Context says this is a treatment section. " + CONTEXT_ONLY_MARKER,
            ContextStatus.RULE_BASED);

    assertEquals(
        "Context says this is a treatment section. " + CONTEXT_ONLY_MARKER + "\n\n" + ORIGINAL_TEXT,
        chunk.embeddingText());
    assertTrue(chunk.embeddingText().contains(CONTEXT_ONLY_MARKER));
    assertEquals(ORIGINAL_TEXT, chunk.lexicalText());
    assertEquals(ORIGINAL_TEXT, chunk.rerankText());
    assertEquals(ORIGINAL_TEXT, chunk.finalContextText());
    assertEquals(ORIGINAL_TEXT, chunk.generationText());
    assertEquals(ORIGINAL_TEXT, chunk.citationText());
    assertEquals(ORIGINAL_TEXT, chunk.displayText());
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.lexicalText(ORIGINAL_TEXT));
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.rerankText(ORIGINAL_TEXT));
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.finalContextText(ORIGINAL_TEXT));
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.generationText(ORIGINAL_TEXT));
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.citationText(ORIGINAL_TEXT));
    assertEquals(ORIGINAL_TEXT, EmbeddingTextPolicy.displayText(ORIGINAL_TEXT));
    assertFalse(chunk.lexicalText().contains(CONTEXT_ONLY_MARKER));
    assertFalse(chunk.rerankText().contains(CONTEXT_ONLY_MARKER));
    assertFalse(chunk.finalContextText().contains(CONTEXT_ONLY_MARKER));
    assertFalse(chunk.generationText().contains(CONTEXT_ONLY_MARKER));
    assertFalse(chunk.citationText().contains(CONTEXT_ONLY_MARKER));
    assertFalse(chunk.displayText().contains(CONTEXT_ONLY_MARKER));
    assertEquals(ORIGINAL_TEXT, chunk.chunk().text());
  }

  @Test
  void missingChunkingStrategyFailsClosedBeforeCacheAccess() {
    final ContextCache cache =
        new ContextCache() {
          @Override
          public java.util.Optional<ContextCacheEntry> get(final ContextCacheKey key) {
            throw new AssertionError("cache must not be accessed");
          }

          @Override
          public void put(final ContextCacheKey key, final ContextCacheEntry entry) {
            throw new AssertionError("cache must not be accessed");
          }
        };
    final ContextualRetrievalService service =
        service(cache, request -> new ContextLlmResponse("unused"), approvedGate());
    final Chunk missingStrategy =
        new Chunk(
            UUID.randomUUID(),
            DOCUMENT_VERSION_ID,
            0,
            "Treatment",
            ORIGINAL_TEXT,
            7,
            new SourceRange(10, 50),
            Map.of("breadcrumb", "Clinical Guide > Treatment"));
    final ContextualRetrievalRequest request =
        new ContextualRetrievalRequest(
            new ContextDocument(
                DOCUMENT_VERSION_ID, "Clinical Guide", "Example Health", "Shared summary"),
            ContextualRetrievalMode.RULE_BASED,
            "prompt-v1",
            List.of(missingStrategy));

    final IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.prepare(request));

    assertEquals("chunking_strategy_id metadata is required", exception.getMessage());
    assertFalse(exception.getMessage().contains(ORIGINAL_TEXT));
  }

  @Test
  void cacheSeparatesChunkingStrategiesAndRejectsConflictingContent() {
    final InMemoryContextCache cache = new InMemoryContextCache();
    final ContextCacheKey structure =
        new ContextCacheKey(
            DOCUMENT_VERSION_ID,
            "structure-v1",
            0,
            ContextualRetrievalMode.RULE_BASED,
            "prompt-v1");
    final ContextCacheKey semantic =
        new ContextCacheKey(
            DOCUMENT_VERSION_ID, "semantic-v1", 0, ContextualRetrievalMode.RULE_BASED, "prompt-v1");

    final ContextCacheEntry structureEntry =
        new ContextCacheEntry("structure context", ContextCacheGenerationStatus.SUCCEEDED);
    final ContextCacheEntry semanticEntry =
        new ContextCacheEntry("semantic context", ContextCacheGenerationStatus.SUCCEEDED);
    cache.put(structure, structureEntry);
    cache.put(semantic, semanticEntry);
    cache.put(structure, structureEntry);

    assertEquals(structureEntry, cache.get(structure).orElseThrow());
    assertEquals(semanticEntry, cache.get(semantic).orElseThrow());
    assertThrows(
        ContextCacheConflictException.class,
        () ->
            cache.put(
                structure,
                new ContextCacheEntry("different", ContextCacheGenerationStatus.SUCCEEDED)));
  }

  private static ContextualRetrievalService service(
      final ContextCache cache, final ContextLlmClient client, final ApprovedCostGate gate) {
    return new ContextualRetrievalService(cache, client, gate);
  }

  private static ContextualRetrievalRequest request(final ContextualRetrievalMode mode) {
    return new ContextualRetrievalRequest(
        new ContextDocument(
            DOCUMENT_VERSION_ID, "Clinical Guide", "Example Health", "Shared summary"),
        mode,
        "prompt-v1",
        List.of(chunk()));
  }

  private static Chunk chunk() {
    return new Chunk(
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        DOCUMENT_VERSION_ID,
        0,
        "Treatment",
        ORIGINAL_TEXT,
        7,
        new SourceRange(10, 50),
        Map.of(
            "breadcrumb", "Clinical Guide > Treatment",
            "chunking_strategy_id", "structure-v1"));
  }

  private static ApprovedCostGate approvedGate() {
    return new ApprovedCostGate(Path.of("approved-cost.json"), hashOf("unused"));
  }

  private static Path writeArtifact(final Path directory, final String content) throws Exception {
    final Path artifact = directory.resolve("approved-cost.json");
    Files.writeString(artifact, content, StandardCharsets.UTF_8);
    return artifact;
  }

  private static String hashOf(final String content) {
    return sha256(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(final byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (final Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
