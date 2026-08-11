package com.medassist.agent.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.ChunkCandidateMetadata;
import com.medassist.agent.state.DraftMetadata;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryCheckpointStoreTest {
  @Test
  void checkpointStoresMetadataProjectionWithoutChunkText() throws Exception {
    final AgentState state =
        AgentState.start(RequestIds.create(), new DeidentifiedQuery("safe query"), Role.RESEARCHER);
    final UUID chunkId = UUID.randomUUID();
    state.applyRoute(
        com.medassist.agent.state.QueryClassification.MIXED,
        Set.of("policy_search"),
        AgentNode.TOOL);
    state.applyToolResult(
        List.of(), List.of(new ChunkCandidateMetadata(chunkId, 10, 25, "sha256:chunk", 0.91, 1)));
    state.applyDraft(new DraftMetadata("sha256:draft", 30, Map.of("model", "stub")));
    final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    store.save(
        new AgentCheckpoint(
            "checkpoint-1",
            state.traceId(),
            state.requestId(),
            1,
            CheckpointPhase.EXIT,
            state.projection(),
            Instant.parse("2026-08-10T08:00:00Z")));

    final String serialized =
        new ObjectMapper().writeValueAsString(store.latest(state.traceId()).orElseThrow().state());

    assertFalse(serialized.contains("full chunk text must never be stored"));
    assertFalse(serialized.contains("Alice Example"));
    assertEquals(1, store.latest(state.traceId()).orElseThrow().state().candidateChunks().size());
  }

  @Test
  void restoreRunsVersionAndPermissionHook() {
    final AgentState state =
        AgentState.start(RequestIds.create(), new DeidentifiedQuery("safe query"), Role.RESEARCHER);
    state.applyRoute(
        com.medassist.agent.state.QueryClassification.POLICY,
        Set.of("policy_search"),
        AgentNode.TOOL);
    final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    store.save(
        new AgentCheckpoint(
            "checkpoint-1",
            state.traceId(),
            state.requestId(),
            1,
            CheckpointPhase.EXIT,
            state.projection(),
            Instant.now()));

    assertEquals(
        state.queryHash(),
        store
            .restore(
                state.traceId(),
                new RecoveryContext(
                    AgentState.CURRENT_STATE_VERSION, Role.RESEARCHER, Set.of("policy_search")),
                new DefaultCheckpointRecoveryValidator())
            .queryHash());
    assertThrows(
        CheckpointRecoveryException.class,
        () ->
            store.restore(
                state.traceId(),
                new RecoveryContext(AgentState.CURRENT_STATE_VERSION, Role.CLINICIAN, Set.of()),
                new DefaultCheckpointRecoveryValidator()));
  }
}
