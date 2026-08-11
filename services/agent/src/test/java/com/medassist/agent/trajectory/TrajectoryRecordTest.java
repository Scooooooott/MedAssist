package com.medassist.agent.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.agent.application.DeidentifiedQuery;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.agent.state.ToolCallRecord;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrajectoryRecordTest {
  @Test
  void stateProjectionCapturesSafeTrajectoryMetadataAndLatency() {
    final String rawQuery = "Patient Alice Example has phone 555-0100";
    final AgentState state =
        AgentState.start(
            RequestIds.create(),
            new DeidentifiedQuery("[PERSON] has phone [REDACTED]"),
            Role.CLINICIAN);
    state.applyRoute(QueryClassification.CLINICAL, Set.of("clinical_search"), AgentNode.TOOL);
    state.applyToolResult(
        List.of(toolCall("allowed-1", "clinical_search"), toolCall("blocked-1", "not_allowed")),
        List.of());
    state.applyCitationSummary(new CitationSummary(4, 3, true));
    state.terminate(TerminationReason.COMPLETED);

    final TrajectoryRecord record =
        TrajectoryRecord.from(
            state,
            QueryClassification.CLINICAL,
            Set.of("clinical_search"),
            List.of(
                event(TrajectoryPhase.ENTRY, 0),
                event(TrajectoryPhase.EXIT, 40),
                event(TrajectoryPhase.EXIT, 60)));

    assertEquals(state.requestId(), record.requestId());
    assertEquals(state.traceId(), record.traceId());
    assertEquals(QueryClassification.CLINICAL, record.queryClass());
    assertEquals(List.of("clinical_search", "not_allowed"), record.actualToolCalls());
    assertEquals(List.of("not_allowed"), record.unauthorizedToolAttempts());
    assertEquals(1, record.unauthorizedToolCallCount());
    assertEquals(new CitationCoverage(4, 3, true), record.citationCoverage());
    assertEquals(100, record.latencyMillis());
    assertEquals("", record.abstainReason());

    final Map<String, Object> exported = TrajectoryExporter.toMap(record);
    final String jsonLine = TrajectoryExporter.toJsonLine(record);
    assertFalse(exported.containsKey("deidentified_query"));
    assertFalse(jsonLine.contains(rawQuery));
    assertTrue(jsonLine.contains(record.queryHash()));
    assertThrows(
        UnsupportedOperationException.class, () -> record.actualToolCalls().add("another_tool"));
    assertThrows(
        UnsupportedOperationException.class, () -> record.expectedTools().add("another_tool"));
  }

  @Test
  void missingExpectedClassIsRepresentedWithoutRawQuery() {
    final AgentState state =
        AgentState.start(
            RequestIds.create(), new DeidentifiedQuery("deidentified only"), Role.RESEARCHER);
    state.terminate(TerminationReason.ABSTAINED);

    final TrajectoryRecord record = TrajectoryRecord.from(state, null, Set.of(), 7);
    final EvalRecord evaluation = TrajectoryEvaluator.evaluate(record);

    assertFalse(record.routeEvaluated());
    assertFalse(evaluation.routeEvaluated());
    assertEquals("insufficient_evidence", record.abstainReason());
    assertEquals(null, TrajectoryExporter.toMap(evaluation).get("expected_query_class"));
    assertEquals(null, TrajectoryExporter.toMap(evaluation).get("route_correct"));
  }

  @Test
  void rawQueryLikeIdentifierIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TrajectoryRecord(
                "request-1",
                "trace-1",
                "Patient Alice Example",
                QueryClassification.CLINICAL,
                null,
                Role.CLINICIAN,
                Set.of(),
                List.of(),
                CitationCoverage.empty(),
                0,
                TerminationReason.COMPLETED,
                "",
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EvalRecord(
                "request-1",
                "trace-1",
                "Patient Alice Example",
                QueryClassification.CLINICAL,
                null,
                Role.CLINICIAN,
                Set.of(),
                List.of(),
                CitationCoverage.empty(),
                0,
                TerminationReason.COMPLETED,
                "",
                List.of(),
                null));
  }

  private static ToolCallRecord toolCall(final String callId, final String toolName) {
    final Instant startedAt = Instant.parse("2026-08-10T08:00:00Z");
    return new ToolCallRecord(
        callId, toolName, "completed", "sha256:input", "sha256:output", startedAt, startedAt);
  }

  private static TrajectoryEvent event(final TrajectoryPhase phase, final long durationMillis) {
    return new TrajectoryEvent(
        "trace-1",
        1,
        AgentNode.TOOL,
        phase,
        1,
        Instant.parse("2026-08-10T08:00:00Z"),
        durationMillis);
  }
}
