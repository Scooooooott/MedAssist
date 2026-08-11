package com.medassist.ingestion.context;

import com.medassist.domain.Chunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Core M2.3 service for rule and provider-generated chunk context. */
public final class ContextualRetrievalService {
  private static final String CHUNKING_STRATEGY_METADATA_KEY = "chunking_strategy_id";
  private final ContextCache cache;
  private final ContextLlmClient llmClient;
  private final ApprovedCostGate costGate;

  public ContextualRetrievalService(
      final ContextCache cache, final ContextLlmClient llmClient, final ApprovedCostGate costGate) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    this.costGate = Objects.requireNonNull(costGate, "costGate");
  }

  public ContextualRetrievalResult prepare(final ContextualRetrievalRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.mode() == ContextualRetrievalMode.LLM_GENERATED) {
      costGate.requireApproved();
    }
    final List<ContextualChunk> results = new ArrayList<>();
    final String sharedPrefix = sharedDocumentPromptPrefix(request.document());
    for (final Chunk chunk : request.chunks()) {
      results.add(prepareChunk(request, chunk, sharedPrefix));
    }
    return new ContextualRetrievalResult(results);
  }

  private ContextualChunk prepareChunk(
      final ContextualRetrievalRequest request, final Chunk chunk, final String sharedPrefix) {
    if (request.mode() == ContextualRetrievalMode.OFF) {
      return new ContextualChunk(chunk, "", ContextStatus.OFF);
    }
    final String chunkingStrategyId = requiredChunkingStrategyId(chunk);
    final ContextCacheKey key =
        new ContextCacheKey(
            chunk.documentVersionId(),
            chunkingStrategyId,
            chunk.ordinal(),
            request.mode(),
            request.promptVersion());
    final Optional<ContextCacheEntry> cached = cache.get(key);
    if (cached.isPresent()) {
      return new ContextualChunk(
          chunk,
          cached.get().contextPrefix(),
          cacheHitStatus(request.mode(), cached.get().generationStatus()));
    }
    if (request.mode() == ContextualRetrievalMode.RULE_BASED) {
      final String rulePrefix = ruleContext(request.document(), chunk);
      cache.put(key, new ContextCacheEntry(rulePrefix, ContextCacheGenerationStatus.SUCCEEDED));
      return new ContextualChunk(chunk, rulePrefix, ContextStatus.RULE_BASED);
    }
    return prepareLlmChunk(request, chunk, sharedPrefix, key);
  }

  private static String requiredChunkingStrategyId(final Chunk chunk) {
    final String strategyId = chunk.metadata().get(CHUNKING_STRATEGY_METADATA_KEY);
    if (strategyId == null || strategyId.isBlank()) {
      throw new IllegalArgumentException("chunking_strategy_id metadata is required");
    }
    return strategyId;
  }

  private ContextualChunk prepareLlmChunk(
      final ContextualRetrievalRequest request,
      final Chunk chunk,
      final String sharedPrefix,
      final ContextCacheKey key) {
    final ContextLlmRequest llmRequest =
        new ContextLlmRequest(
            chunk.documentVersionId(),
            chunk.ordinal(),
            request.promptVersion(),
            sharedPrefix,
            chunk.text());
    final ContextLlmResponse response;
    try {
      response = llmClient.generate(llmRequest);
    } catch (final RuntimeException exception) {
      final String rulePrefix = ruleContext(request.document(), chunk);
      cache.put(key, new ContextCacheEntry(rulePrefix, ContextCacheGenerationStatus.RULE_FALLBACK));
      return new ContextualChunk(chunk, rulePrefix, ContextStatus.LLM_FALLBACK_RULE_BASED);
    }
    cache.put(
        key,
        new ContextCacheEntry(response.contextPrefix(), ContextCacheGenerationStatus.SUCCEEDED));
    return new ContextualChunk(chunk, response.contextPrefix(), ContextStatus.LLM_GENERATED);
  }

  private static ContextStatus cacheHitStatus(
      final ContextualRetrievalMode mode, final ContextCacheGenerationStatus generationStatus) {
    if (generationStatus == ContextCacheGenerationStatus.RULE_FALLBACK) {
      if (mode != ContextualRetrievalMode.LLM_GENERATED) {
        throw new ContextCacheException("rule fallback has invalid cache mode");
      }
      return ContextStatus.LLM_FALLBACK_RULE_BASED_CACHE_HIT;
    }
    return mode == ContextualRetrievalMode.LLM_GENERATED
        ? ContextStatus.LLM_GENERATED_CACHE_HIT
        : mode == ContextualRetrievalMode.RULE_BASED
            ? ContextStatus.RULE_BASED_CACHE_HIT
            : ContextStatus.OFF;
  }

  static String sharedDocumentPromptPrefix(final ContextDocument document) {
    return "Document title: "
        + document.title()
        + "\nPublisher: "
        + document.publisher()
        + "\nDocument summary: "
        + document.sharedDocumentSummary();
  }

  static String ruleContext(final ContextDocument document, final Chunk chunk) {
    final String breadcrumb = chunk.metadata().getOrDefault("breadcrumb", "Unknown");
    return sharedDocumentPromptPrefix(document) + "\nBreadcrumb: " + breadcrumb;
  }
}
