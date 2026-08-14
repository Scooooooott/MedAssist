package com.medassist.agent.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.api.dto.AgentRequest;
import com.medassist.agent.application.AgentEntryService;
import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.execution.AgentExecutionEngine;
import com.medassist.agent.execution.AgentExecutionResult;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentStateSafetyTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rawQueryIsConsumedBeforeStateCreation() throws Exception {
    final String rawQuery = "Patient Alice Example has phone 555-0100";
    final AtomicReference<AgentState> captured = new AtomicReference<>();
    final AgentExecutionEngine engine =
        state -> {
          captured.set(state);
          state.terminate(TerminationReason.COMPLETED);
          return new AgentExecutionResult(state, "safe response");
        };
    final AgentEntryService service =
        new AgentEntryService(
            ignored -> new DeidentifiedQuery("Person [REDACTED] has phone [REDACTED]"), engine);

    service.execute(new AgentRequest(rawQuery), Role.CLINICIAN);

    assertFalse(captured.get().deidentifiedQuery().contains(rawQuery));
    assertFalse(objectMapper.writeValueAsString(captured.get().projection()).contains(rawQuery));
  }

  @Test
  void stateSerializationContainsHashButNoRawQuery() throws Exception {
    final String rawQuery = "Alice Example diagnosis";
    final AgentState state =
        AgentState.start(
            RequestIds.create(), new DeidentifiedQuery("[PERSON] diagnosis"), Role.CLINICIAN);
    state.applyDraft(new DraftMetadata("sha256:draft", 12, Map.of("provider", "test")));

    final String serialized = objectMapper.writeValueAsString(state.projection());

    assertFalse(serialized.contains(rawQuery));
    assertTrue(serialized.contains(state.queryHash()));
    assertTrue(serialized.contains("[PERSON] diagnosis"));
  }

  @Test
  void stateKeepsTheIngressHashForAuditCorrelation() {
    final AgentState state =
        AgentState.start(
            RequestIds.create(),
            new DeidentifiedQuery("[PERSON] diagnosis", "sha256:original-query"),
            Role.CLINICIAN);

    assertTrue(state.queryHash().equals("sha256:original-query"));
  }
}
