package com.medassist.agent.routing;

import com.medassist.agent.state.QueryClassification;
import com.medassist.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ToolRegistry {
  List<ToolDefinition> definitions();

  Set<String> toolsFor(Role role);

  Set<String> toolsFor(Role role, QueryClassification classification, String query);

  default Set<String> allowedTools(final Role role) {
    return toolsFor(role);
  }

  default Set<String> allowedTools(
      final Role role, final QueryClassification classification, final String query) {
    return toolsFor(role, classification, query);
  }

  default Optional<ToolDefinition> find(final String toolName) {
    return definitions().stream()
        .filter(definition -> definition.name().equals(toolName))
        .findFirst();
  }

  default boolean isAllowed(
      final Role role,
      final QueryClassification classification,
      final String query,
      final String toolName) {
    return toolsFor(role, classification, query).contains(toolName);
  }
}
