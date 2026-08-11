package com.medassist.agent.routing;

import com.medassist.agent.state.QueryClassification;
import com.medassist.domain.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The request-scoped allowlist for the M3 agent tools. */
public final class DefaultToolRegistry implements ToolRegistry {
  public static final String POLICY_SEARCH = "policy_search";
  public static final String CLINICAL_SEARCH = "clinical_search";
  public static final String STRUCTURED_QUERY = "structured_query";

  private static final Set<QueryClassification> POLICY_CLASSIFICATIONS =
      Set.of(QueryClassification.POLICY, QueryClassification.MIXED);
  private static final Set<QueryClassification> CLINICAL_CLASSIFICATIONS =
      Set.of(QueryClassification.CLINICAL, QueryClassification.MIXED);
  private static final Set<QueryClassification> STRUCTURED_CLASSIFICATIONS =
      Set.of(QueryClassification.STRUCTURED, QueryClassification.MIXED);

  private final List<ToolDefinition> definitions;

  public DefaultToolRegistry() {
    definitions =
        List.of(
            new ToolDefinition(
                POLICY_SEARCH,
                Set.of(Role.CLINICIAN, Role.RESEARCHER, Role.ADMIN),
                POLICY_CLASSIFICATIONS,
                Set.of()),
            new ToolDefinition(
                CLINICAL_SEARCH, Set.of(Role.CLINICIAN), CLINICAL_CLASSIFICATIONS, Set.of()),
            new ToolDefinition(
                STRUCTURED_QUERY,
                Set.of(Role.CLINICIAN, Role.RESEARCHER),
                STRUCTURED_CLASSIFICATIONS,
                Set.of(Role.RESEARCHER)));
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
        "统计",
        "聚合",
        "分布",
        "比例",
        "均值",
        "平均",
        "总数");
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
