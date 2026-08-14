package com.medassist.agent.execution;

import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.state.AgentRetrievalFilters;
import com.medassist.contracts.v1.ContextualRetrievalMode;
import com.medassist.contracts.v1.DocumentMetadata;
import com.medassist.contracts.v1.RequestMetadata;
import com.medassist.contracts.v1.RetrievalFilters;
import com.medassist.contracts.v1.RetrievalMode;
import com.medassist.contracts.v1.RetrievalResult;
import com.medassist.contracts.v1.RetrievalServiceGrpc;
import com.medassist.contracts.v1.SearchRequest;
import com.medassist.contracts.v1.SearchResponse;
import com.medassist.contracts.v1.SourceRange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Retrieval RPC adapter. Raw chunk text is retained only in the transient backend result. */
public final class RetrievalGrpcToolBackend implements ToolBackend {
  private final RetrievalServiceGrpc.RetrievalServiceBlockingStub stub;
  private final Duration rpcTimeout;

  public RetrievalGrpcToolBackend(final RetrievalServiceGrpc.RetrievalServiceBlockingStub stub) {
    this(stub, null);
  }

  public RetrievalGrpcToolBackend(
      final RetrievalServiceGrpc.RetrievalServiceBlockingStub stub, final Duration rpcTimeout) {
    this.stub = java.util.Objects.requireNonNull(stub, "stub");
    if (rpcTimeout != null && (rpcTimeout.isZero() || rpcTimeout.isNegative())) {
      throw new IllegalArgumentException("rpcTimeout must be positive");
    }
    this.rpcTimeout = rpcTimeout;
  }

  @Override
  public ToolBackendResult execute(final ToolInvocationRequest request) {
    if (!DefaultToolRegistry.POLICY_SEARCH.equals(request.toolName())
        && !DefaultToolRegistry.CLINICAL_SEARCH.equals(request.toolName())) {
      throw new IllegalArgumentException("retrieval backend does not support this tool");
    }
    final RetrievalServiceGrpc.RetrievalServiceBlockingStub callStub =
        rpcTimeout == null
            ? stub
            : stub.withDeadlineAfter(rpcTimeout.toNanos(), TimeUnit.NANOSECONDS);
    final SearchResponse response = callStub.search(toRequest(request));
    if (response.hasError()) {
      throw new IllegalStateException("retrieval service returned an error");
    }
    final List<ToolBackendChunk> chunks = new ArrayList<>();
    for (int index = 0; index < response.getResultsCount(); index++) {
      chunks.add(toChunk(response.getResults(index), index));
    }
    return new ToolBackendResult(request.toolName(), chunks, List.of());
  }

  private SearchRequest toRequest(final ToolInvocationRequest request) {
    return SearchRequest.newBuilder()
        .setMetadata(
            RequestMetadata.newBuilder()
                .setTraceId(request.traceId())
                .setRequestId(request.requestId())
                .setActor("agent")
                .setRole(request.role().name()))
        .setQuery(request.query())
        .setTopK(request.topK())
        .setFilters(toFilters(request.toolName(), request.filters()))
        .setRole(request.role().name())
        .setRetrievalMode(RetrievalMode.RETRIEVAL_MODE_HYBRID)
        .setRerankEnabled(false)
        .setIncludeSuperseded(false)
        .setContextualRetrievalMode(ContextualRetrievalMode.CONTEXTUAL_RETRIEVAL_MODE_OFF)
        .build();
  }

  private RetrievalFilters toFilters(final String toolName, final AgentRetrievalFilters requested) {
    final List<String> allowedDocTypes =
        DefaultToolRegistry.POLICY_SEARCH.equals(toolName)
            ? List.of("POLICY", "GUIDELINE")
            : List.of("CLINICAL_NOTE");
    if (!requested.docTypes().isEmpty()
        && !Set.copyOf(allowedDocTypes).containsAll(requested.docTypes())) {
      throw new IllegalArgumentException("requested document type is not allowed for this tool");
    }
    final List<String> docTypes =
        requested.docTypes().isEmpty()
            ? allowedDocTypes
            : requested.docTypes().stream().sorted().toList();
    final RetrievalFilters.Builder filters = RetrievalFilters.newBuilder();
    filters.addAllDocType(docTypes);
    filters.addAllPublisher(requested.publishers().stream().sorted().toList());
    filters.addAllSectionType(requested.sectionTypes().stream().sorted().toList());
    if (requested.effectiveDateFrom() != null) {
      filters.setEffectiveDateFrom(requested.effectiveDateFrom().toString());
    }
    if (requested.effectiveDateTo() != null) {
      filters.setEffectiveDateTo(requested.effectiveDateTo().toString());
    }
    return filters.build();
  }

  private ToolBackendChunk toChunk(final RetrievalResult result, final int index) {
    final UUID chunkId = parseUuid(result.getChunkId());
    final SourceRange range = result.getSourceRange();
    final DocumentMetadata source = result.getSource();
    final String version = firstNonBlank(source.getVersion(), result.getDocumentVersionId());
    final String sourceName = firstNonBlank(source.getDocType(), "retrieval");
    final String locator =
        firstNonBlank(result.getSectionPath(), result.getChunkId() + "#" + result.getOrdinal());
    final int rank = result.getOrdinal() > 0 ? result.getOrdinal() : index + 1;
    final long rangeStart = Math.max(0L, range.getStart());
    final long rangeEnd = Math.max(rangeStart, range.getEnd());
    return new ToolBackendChunk(
        chunkId,
        rangeStart,
        rangeEnd,
        sha256(result.getText()),
        result.getScore(),
        rank,
        version,
        sourceName,
        locator,
        result.getText());
  }

  private UUID parseUuid(final String value) {
    try {
      return UUID.fromString(value);
    } catch (final IllegalArgumentException exception) {
      return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String firstNonBlank(final String first, final String fallback) {
    return first == null || first.isBlank()
        ? (fallback == null || fallback.isBlank() ? "unknown" : fallback)
        : first;
  }

  private String sha256(final String value) {
    try {
      final byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (final byte valueByte : bytes) {
        hex.append(String.format("%02x", valueByte));
      }
      return "sha256:" + hex;
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
