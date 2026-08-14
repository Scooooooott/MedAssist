package com.medassist.agent.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medassist.agent.application.ChatMessage;
import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.execution.RuntimeSafetyEvidence;
import com.medassist.agent.execution.SafeAggregationColumn;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mutable execution state whose fields are restricted to safe, deidentified projections. */
public final class AgentState implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  public static final String CURRENT_STATE_VERSION = "agent-state-v3";

  private final String stateVersion;
  private final String traceId;
  private final String requestId;
  private final String deidentifiedQuery;
  private final String queryHash;
  private final Role role;
  private final AgentRetrievalFilters retrievalFilters;
  private QueryClassification classification;
  private Set<String> allowedTools;
  private List<ChunkCandidateMetadata> candidateChunks;
  private List<SafeAggregationColumn> aggregationColumns;
  private List<ToolCallRecord> toolCalls;
  private DraftMetadata draftMetadata;
  private CitationSummary citationSummary;
  private int retryCount;
  private AgentNode currentNode;
  private long stepCount;
  private TerminationReason terminationReason;
  private transient RuntimeSafetyEvidence runtimeSafetyEvidence;
  private transient List<ChatMessage> chatHistory;

  private AgentState(
      final String stateVersion,
      final String traceId,
      final String requestId,
      final String deidentifiedQuery,
      final String queryHash,
      final Role role,
      final AgentRetrievalFilters retrievalFilters) {
    this.stateVersion = Objects.requireNonNull(stateVersion, "stateVersion");
    this.traceId = Objects.requireNonNull(traceId, "traceId");
    this.requestId = Objects.requireNonNull(requestId, "requestId");
    this.deidentifiedQuery = Objects.requireNonNull(deidentifiedQuery, "deidentifiedQuery");
    this.queryHash = Objects.requireNonNull(queryHash, "queryHash");
    this.role = Objects.requireNonNull(role, "role");
    this.retrievalFilters = Objects.requireNonNull(retrievalFilters, "retrievalFilters");
    this.classification = QueryClassification.UNKNOWN;
    this.allowedTools = Set.of();
    this.candidateChunks = List.of();
    this.aggregationColumns = List.of();
    this.toolCalls = List.of();
    this.citationSummary = CitationSummary.empty();
    this.currentNode = AgentNode.ROUTE;
    this.runtimeSafetyEvidence = RuntimeSafetyEvidence.empty();
    this.chatHistory = List.of();
  }

  public static AgentState start(
      final RequestIds requestIds, final DeidentifiedQuery query, final Role role) {
    return start(requestIds, query, role, AgentRetrievalFilters.empty());
  }

  public static AgentState start(
      final RequestIds requestIds,
      final DeidentifiedQuery query,
      final Role role,
      final AgentRetrievalFilters retrievalFilters) {
    Objects.requireNonNull(requestIds, "requestIds");
    Objects.requireNonNull(query, "query");
    return new AgentState(
        CURRENT_STATE_VERSION,
        requestIds.traceId(),
        requestIds.requestId(),
        query.value(),
        query.originalQueryHash(),
        role,
        retrievalFilters);
  }

  public static AgentState restore(final AgentStateProjection projection) {
    Objects.requireNonNull(projection, "projection");
    final AgentState state =
        new AgentState(
            projection.stateVersion(),
            projection.traceId(),
            projection.requestId(),
            projection.deidentifiedQuery(),
            projection.queryHash(),
            projection.role(),
            projection.retrievalFilters());
    state.classification = projection.classification();
    state.allowedTools = Set.copyOf(projection.allowedTools());
    state.candidateChunks = List.copyOf(projection.candidateChunks());
    state.aggregationColumns = List.copyOf(projection.aggregationColumns());
    state.toolCalls = List.copyOf(projection.toolCalls());
    state.draftMetadata = projection.draftMetadata();
    state.citationSummary = projection.citationSummary();
    state.retryCount = projection.retryCount();
    state.currentNode = projection.currentNode();
    state.stepCount = projection.stepCount();
    state.terminationReason = projection.terminationReason();
    return state;
  }

  public String stateVersion() {
    return stateVersion;
  }

  public String traceId() {
    return traceId;
  }

  public String requestId() {
    return requestId;
  }

  public String deidentifiedQuery() {
    return deidentifiedQuery;
  }

  public String queryHash() {
    return queryHash;
  }

  public Role role() {
    return role;
  }

  public AgentRetrievalFilters retrievalFilters() {
    return retrievalFilters;
  }

  public QueryClassification classification() {
    return classification;
  }

  public Set<String> allowedTools() {
    return allowedTools;
  }

  public List<ChunkCandidateMetadata> candidateChunks() {
    return candidateChunks;
  }

  public List<SafeAggregationColumn> aggregationColumns() {
    return aggregationColumns;
  }

  public List<ToolCallRecord> toolCalls() {
    return toolCalls;
  }

  public DraftMetadata draftMetadata() {
    return draftMetadata;
  }

  public CitationSummary citationSummary() {
    return citationSummary;
  }

  public int retryCount() {
    return retryCount;
  }

  public AgentNode currentNode() {
    return currentNode;
  }

  public long stepCount() {
    return stepCount;
  }

  public TerminationReason terminationReason() {
    return terminationReason;
  }

  @JsonIgnore
  public RuntimeSafetyEvidence runtimeSafetyEvidence() {
    return runtimeSafetyEvidence;
  }

  @JsonIgnore
  public List<ChatMessage> chatHistory() {
    return chatHistory;
  }

  public void applyChatHistory(final List<ChatMessage> history) {
    chatHistory = List.copyOf(Objects.requireNonNull(history, "history"));
  }

  public void applyRoute(
      final QueryClassification newClassification,
      final Set<String> newAllowedTools,
      final AgentNode nextNode) {
    classification = Objects.requireNonNull(newClassification, "newClassification");
    allowedTools = Set.copyOf(Objects.requireNonNull(newAllowedTools, "newAllowedTools"));
    transitionTo(nextNode);
  }

  public void applyToolResult(
      final List<ToolCallRecord> newToolCalls, final List<ChunkCandidateMetadata> chunks) {
    applyToolResult(newToolCalls, chunks, List.of(), RuntimeSafetyEvidence.empty());
  }

  public void applyToolResult(
      final List<ToolCallRecord> newToolCalls,
      final List<ChunkCandidateMetadata> chunks,
      final List<SafeAggregationColumn> newAggregationColumns) {
    applyToolResult(newToolCalls, chunks, newAggregationColumns, RuntimeSafetyEvidence.empty());
  }

  public void applyToolResult(
      final List<ToolCallRecord> newToolCalls,
      final List<ChunkCandidateMetadata> chunks,
      final RuntimeSafetyEvidence newRuntimeSafetyEvidence) {
    applyToolResult(newToolCalls, chunks, List.of(), newRuntimeSafetyEvidence);
  }

  public void applyToolResult(
      final List<ToolCallRecord> newToolCalls,
      final List<ChunkCandidateMetadata> chunks,
      final List<SafeAggregationColumn> newAggregationColumns,
      final RuntimeSafetyEvidence newRuntimeSafetyEvidence) {
    final List<ToolCallRecord> calls = new ArrayList<>(toolCalls);
    calls.addAll(Objects.requireNonNull(newToolCalls, "newToolCalls"));
    toolCalls = List.copyOf(calls);
    final List<ChunkCandidateMetadata> candidates = new ArrayList<>(candidateChunks);
    candidates.addAll(Objects.requireNonNull(chunks, "chunks"));
    candidateChunks = List.copyOf(candidates);
    final List<SafeAggregationColumn> aggregations = new ArrayList<>(aggregationColumns);
    aggregations.addAll(Objects.requireNonNull(newAggregationColumns, "newAggregationColumns"));
    aggregationColumns = List.copyOf(aggregations);
    final RuntimeSafetyEvidence incoming =
        Objects.requireNonNull(newRuntimeSafetyEvidence, "newRuntimeSafetyEvidence");
    final LinkedHashMap<java.util.UUID, com.medassist.agent.execution.RuntimeEvidenceChunk>
        evidenceById = new LinkedHashMap<>();
    runtimeSafetyEvidence.chunks().forEach(chunk -> evidenceById.put(chunk.chunkId(), chunk));
    incoming.chunks().forEach(chunk -> evidenceById.putIfAbsent(chunk.chunkId(), chunk));
    runtimeSafetyEvidence = new RuntimeSafetyEvidence(new ArrayList<>(evidenceById.values()));
  }

  public void applyDraft(final DraftMetadata newDraftMetadata) {
    draftMetadata = Objects.requireNonNull(newDraftMetadata, "newDraftMetadata");
  }

  public void applyCitationSummary(final CitationSummary newCitationSummary) {
    citationSummary = Objects.requireNonNull(newCitationSummary, "newCitationSummary");
  }

  public void incrementRetry() {
    retryCount++;
  }

  public void incrementStep() {
    stepCount++;
  }

  public void transitionTo(final AgentNode nextNode) {
    currentNode = Objects.requireNonNull(nextNode, "nextNode");
  }

  public void terminate(final TerminationReason reason) {
    if (terminationReason == null) {
      terminationReason = Objects.requireNonNull(reason, "reason");
    }
  }

  public void forceTerminate(final TerminationReason reason) {
    terminationReason = Objects.requireNonNull(reason, "reason");
  }

  public AgentStateProjection projection() {
    return new AgentStateProjection(
        stateVersion,
        traceId,
        requestId,
        deidentifiedQuery,
        queryHash,
        role,
        retrievalFilters,
        classification,
        allowedTools,
        candidateChunks,
        aggregationColumns,
        toolCalls,
        draftMetadata,
        citationSummary,
        retryCount,
        currentNode,
        stepCount,
        terminationReason);
  }
}
