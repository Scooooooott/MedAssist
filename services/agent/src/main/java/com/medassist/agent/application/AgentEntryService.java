package com.medassist.agent.application;

import com.medassist.agent.api.dto.AgentRequest;
import com.medassist.agent.api.dto.AgentResponse;
import com.medassist.agent.config.ChatMemoryProperties;
import com.medassist.agent.execution.AgentExecutionEngine;
import com.medassist.agent.execution.AgentExecutionResult;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.CitationSummary;
import com.medassist.agent.state.TerminationReason;
import com.medassist.common.RequestIds;
import com.medassist.domain.Role;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentEntryService {
  private final QueryDeidentifier queryDeidentifier;
  private final AgentExecutionEngine executionEngine;
  private final ChatMemory chatMemory;

  @Autowired
  public AgentEntryService(
      final QueryDeidentifier queryDeidentifier,
      final AgentExecutionEngine executionEngine,
      final ChatMemory chatMemory) {
    this.queryDeidentifier = Objects.requireNonNull(queryDeidentifier, "queryDeidentifier");
    this.executionEngine = Objects.requireNonNull(executionEngine, "executionEngine");
    this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory");
  }

  public AgentEntryService(
      final QueryDeidentifier queryDeidentifier, final AgentExecutionEngine executionEngine) {
    this(queryDeidentifier, executionEngine, defaultChatMemory());
  }

  private static ChatMemory defaultChatMemory() {
    final ChatMemoryProperties defaults = ChatMemoryProperties.defaults();
    return new InMemoryChatMemory(defaults.maxMessages(), defaults.maxCharacters());
  }

  public AgentResponse execute(final AgentRequest request) {
    Objects.requireNonNull(request, "request");
    final RequestIds requestIds = RequestIds.create();
    final Role role;
    try {
      role = parseRole(request.role());
    } catch (final IllegalArgumentException exception) {
      return new AgentResponse(
          requestIds.traceId(),
          requestIds.requestId(),
          null,
          true,
          "The supplied role is not authorized.",
          null,
          null,
          CitationSummary.empty(),
          TerminationReason.ABSTAINED);
    }
    final DeidentifiedQuery deidentifiedQuery;
    try {
      deidentifiedQuery =
          queryDeidentifier.deidentify(
              request.query(),
              new DeidentificationMetadata(requestIds.traceId(), requestIds.requestId(), role));
    } catch (final DeidentificationException exception) {
      return new AgentResponse(
          requestIds.traceId(),
          requestIds.requestId(),
          null,
          true,
          "Request deidentification failed.",
          null,
          null,
          CitationSummary.empty(),
          TerminationReason.DEIDENTIFICATION_FAILED);
    }
    final AgentState state = AgentState.start(requestIds, deidentifiedQuery, role);
    final String conversationId =
        request.conversationId() == null || request.conversationId().isBlank()
            ? requestIds.traceId()
            : request.conversationId();
    state.applyChatHistory(chatMemory.read(conversationId));
    final AgentExecutionResult result = executionEngine.execute(state);
    if (!result.abstained() && result.answer() != null) {
      chatMemory.append(conversationId, new ChatMessage("user", deidentifiedQuery.value()));
      chatMemory.append(conversationId, new ChatMessage("assistant", result.answer()));
    }
    final TerminationReason terminationReason = result.state().terminationReason();
    return new AgentResponse(
        result.state().traceId(),
        result.state().requestId(),
        result.answer(),
        result.abstained(),
        result.abstained() ? abstainReason(terminationReason) : "",
        result.state().queryHash(),
        result.state().draftMetadata(),
        result.state().citationSummary(),
        terminationReason);
  }

  private Role parseRole(final String value) {
    if (value == null || value.isBlank()) {
      return Role.CLINICIAN;
    }
    try {
      return Role.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException exception) {
      throw new IllegalArgumentException("unsupported role", exception);
    }
  }

  private String abstainReason(final TerminationReason reason) {
    return switch (reason) {
      case ABSTAINED -> "The agent could not establish sufficient evidence.";
      case MAX_STEPS -> "The agent execution step limit was reached.";
      case TIMEOUT -> "The agent execution timeout was reached.";
      case DEIDENTIFICATION_FAILED -> "Request deidentification failed.";
      case RECOVERY_REJECTED -> "The checkpoint could not be safely restored.";
      case EXECUTION_ERROR -> "The agent execution failed closed.";
      case COMPLETED -> "";
    };
  }
}
