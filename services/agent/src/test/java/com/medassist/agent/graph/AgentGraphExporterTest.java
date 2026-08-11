package com.medassist.agent.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentGraphExporterTest {
  @Test
  void exportsTheExplicitStateGraphAsMermaidAndDot() {
    final String mermaid = AgentGraphExporter.mermaid();
    final String dot = AgentGraphExporter.dot();

    assertTrue(mermaid.contains("ROUTE --> TOOL"));
    assertTrue(mermaid.contains("VERIFY --> RETRY"));
    assertTrue(mermaid.contains("VERIFY --> ABSTAIN"));
    assertTrue(dot.contains("GENERATE -> VERIFY"));
    assertTrue(dot.contains("RESPOND -> RESPOND"));
  }
}
