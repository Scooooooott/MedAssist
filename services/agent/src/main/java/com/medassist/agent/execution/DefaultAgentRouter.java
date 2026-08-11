package com.medassist.agent.execution;

import com.medassist.agent.routing.DefaultQueryClassifier;
import com.medassist.agent.routing.DefaultToolRegistry;
import com.medassist.agent.routing.QueryClassifier;
import com.medassist.agent.routing.ToolRegistry;
import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.QueryClassification;
import java.util.Objects;
import java.util.Set;

public final class DefaultAgentRouter implements AgentRouter {
  private final QueryClassifier classifier;
  private final ToolRegistry toolRegistry;

  public DefaultAgentRouter() {
    this(new DefaultQueryClassifier(), new DefaultToolRegistry());
  }

  public DefaultAgentRouter(final QueryClassifier classifier) {
    this(classifier, new DefaultToolRegistry());
  }

  public DefaultAgentRouter(final QueryClassifier classifier, final ToolRegistry toolRegistry) {
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
  }

  @Override
  public RouteDecision route(final AgentState state) {
    Objects.requireNonNull(state, "state");
    QueryClassification classification = classifier.classify(state);
    if (classification == null || classification == QueryClassification.UNKNOWN) {
      classification = QueryClassification.MIXED;
    }
    final Set<String> allowedTools =
        toolRegistry.toolsFor(state.role(), classification, state.deidentifiedQuery());
    final AgentNode nextNode =
        classification == QueryClassification.OUT_OF_SCOPE || allowedTools.isEmpty()
            ? AgentNode.ABSTAIN
            : AgentNode.TOOL;
    return new RouteDecision(classification, allowedTools, nextNode);
  }

  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }
}
