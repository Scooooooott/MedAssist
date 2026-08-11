package com.medassist.agent.execution;

import com.medassist.agent.state.AgentNode;
import com.medassist.agent.state.QueryClassification;
import java.util.Objects;
import java.util.Set;

public record RouteDecision(
    QueryClassification classification, Set<String> allowedTools, AgentNode nextNode) {
  public RouteDecision {
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(allowedTools, "allowedTools");
    Objects.requireNonNull(nextNode, "nextNode");
    allowedTools = Set.copyOf(allowedTools);
    if (nextNode != AgentNode.TOOL && nextNode != AgentNode.ABSTAIN) {
      throw new IllegalArgumentException("route may only enter TOOL or ABSTAIN");
    }
    if (classification == QueryClassification.OUT_OF_SCOPE
        && (nextNode != AgentNode.ABSTAIN || !allowedTools.isEmpty())) {
      throw new IllegalArgumentException("out-of-scope routes must abstain without tools");
    }
  }
}
