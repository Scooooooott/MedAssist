package com.medassist.agent.graph;

import com.medassist.agent.state.AgentNode;
import java.util.List;

public final class AgentGraphExporter {
  private static final List<GraphEdge> EDGES =
      List.of(
          new GraphEdge(AgentNode.ROUTE, AgentNode.TOOL, "in scope"),
          new GraphEdge(AgentNode.ROUTE, AgentNode.ABSTAIN, "out of scope"),
          new GraphEdge(AgentNode.TOOL, AgentNode.GENERATE, "tool result"),
          new GraphEdge(AgentNode.GENERATE, AgentNode.VERIFY, "draft ready"),
          new GraphEdge(AgentNode.VERIFY, AgentNode.RESPOND, "verified"),
          new GraphEdge(AgentNode.VERIFY, AgentNode.RETRY, "retryable"),
          new GraphEdge(AgentNode.VERIFY, AgentNode.ABSTAIN, "not sufficient"),
          new GraphEdge(AgentNode.RETRY, AgentNode.TOOL, "retry budget"),
          new GraphEdge(AgentNode.RESPOND, AgentNode.RESPOND, "terminate"),
          new GraphEdge(AgentNode.ABSTAIN, AgentNode.ABSTAIN, "terminate"));

  private AgentGraphExporter() {}

  public static String mermaid() {
    final StringBuilder output = new StringBuilder("stateDiagram-v2\n");
    for (final GraphEdge edge : EDGES) {
      output
          .append("  ")
          .append(edge.from())
          .append(" --> ")
          .append(edge.to())
          .append(" : ")
          .append(edge.label())
          .append('\n');
    }
    return output.toString();
  }

  public static String dot() {
    final StringBuilder output = new StringBuilder("digraph AgentState {\n");
    output.append("  rankdir=LR;\n");
    for (final AgentNode node : AgentNode.values()) {
      output.append("  ").append(node).append(";\n");
    }
    for (final GraphEdge edge : EDGES) {
      output
          .append("  ")
          .append(edge.from())
          .append(" -> ")
          .append(edge.to())
          .append(" [label=\"")
          .append(edge.label())
          .append("\"];\n");
    }
    return output.append("}\n").toString();
  }

  private record GraphEdge(AgentNode from, AgentNode to, String label) {}
}
