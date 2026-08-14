package com.medassist.agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.state.QueryClassification;
import com.medassist.domain.Role;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The request-scoped allowlist for the M3 agent tools. */
public final class DefaultToolRegistry implements ToolRegistry {
  public static final String POLICY_SEARCH = "policy_search";
  public static final String CLINICAL_SEARCH = "clinical_search";
  public static final String STRUCTURED_QUERY = "structured_query";

  private static final String TOOL_MAP_RESOURCE = "/governance/tool-map.json";

  private final List<ToolDefinition> definitions;

  public DefaultToolRegistry() {
    this(loadPolicyDefinitions());
  }

  public DefaultToolRegistry(final List<ToolDefinition> definitions) {
    Objects.requireNonNull(definitions, "definitions");
    if (definitions.isEmpty()) {
      throw new IllegalArgumentException("tool registry must not be empty");
    }
    final List<ToolDefinition> copy = new ArrayList<>(definitions);
    final Set<String> names = new LinkedHashSet<>();
    copy.forEach(
        definition -> {
          if (!names.add(definition.name())) {
            throw new IllegalArgumentException("duplicate tool: " + definition.name());
          }
        });
    this.definitions = List.copyOf(copy);
  }

  @Override
  public List<ToolDefinition> definitions() {
    return definitions;
  }

  @Override
  public Set<String> toolsFor(final Role role) {
    Objects.requireNonNull(role, "role");
    final Set<String> tools = new LinkedHashSet<>();
    definitions.stream()
        .filter(definition -> definition.allowedFor(role))
        .forEach(definition -> tools.add(definition.name()));
    return immutableSet(tools);
  }

  @Override
  public Set<String> toolsFor(
      final Role role, final QueryClassification classification, final String query) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(classification, "classification");
    final Set<String> tools = new LinkedHashSet<>();
    if (classification == QueryClassification.OUT_OF_SCOPE
        || classification == QueryClassification.UNKNOWN) {
      return Set.of();
    }
    definitions.forEach(
        definition -> {
          if (definition.allowedFor(role)
              && definition.supports(classification)
              && (!definition.aggregateOnlyFor(role) || isAggregationQuery(query))) {
            tools.add(definition.name());
          }
        });
    return immutableSet(tools);
  }

  public static boolean isAggregationQuery(final String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    final String normalized = query.toLowerCase(java.util.Locale.ROOT);
    return containsAny(
        normalized,
        "count",
        "sum",
        "average",
        "avg",
        "mean",
        "median",
        "percentile",
        "group by",
        "aggregate",
        "aggregation",
        "distribution",
        "cohort",
        "how many",
        "number of",
        "\u7f01\u71bb\ue178",
        "\u9471\u6c2c\u608e",
        "\u9352\u55d7\u7af7",
        "\u59e3\u65be\u7de5",
        "\u9367\u56e7\u20ac",
        "\u9a9e\u51b2\u6f4e",
        "\u93ac\u7ed8\u669f");
  }

  private static List<ToolDefinition> loadPolicyDefinitions() {
    try (InputStream resource = DefaultToolRegistry.class.getResourceAsStream(TOOL_MAP_RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException("generated agent tool policy is missing");
      }
      final JsonNode policy = new ObjectMapper().readTree(resource).path("policy");
      if (!policy.isObject()) {
        throw new IllegalStateException("generated agent tool policy is invalid");
      }
      final List<ToolDefinition> definitions = new ArrayList<>();
      final Iterator<Map.Entry<String, JsonNode>> tools = policy.fields();
      while (tools.hasNext()) {
        final Map.Entry<String, JsonNode> entry = tools.next();
        final JsonNode definition = entry.getValue();
        definitions.add(
            new ToolDefinition(
                entry.getKey(),
                enumSet(definition.path("roles"), Role.class),
                enumSet(definition.path("query_classifications"), QueryClassification.class),
                enumSet(definition.path("aggregate_only_roles"), Role.class)));
      }
      return List.copyOf(definitions);
    } catch (final IOException | RuntimeException exception) {
      if (exception instanceof IllegalStateException stateException) {
        throw stateException;
      }
      throw new IllegalStateException("generated agent tool policy is invalid", exception);
    }
  }

  private static <E extends Enum<E>> Set<E> enumSet(final JsonNode node, final Class<E> enumType) {
    if (!node.isArray()) {
      throw new IllegalStateException("generated agent tool policy has an invalid scope");
    }
    final Set<E> values = new LinkedHashSet<>();
    node.forEach(
        value -> {
          if (!value.isTextual()) {
            throw new IllegalStateException("generated agent tool policy has an invalid scope");
          }
          try {
            values.add(Enum.valueOf(enumType, value.textValue()));
          } catch (final IllegalArgumentException exception) {
            throw new IllegalStateException("generated agent tool policy has an unknown scope");
          }
        });
    return Set.copyOf(values);
  }

  private static boolean containsAny(final String query, final String... signals) {
    for (final String signal : signals) {
      if (query.contains(signal)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> immutableSet(final Set<String> tools) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(tools));
  }
}
