package com.medassist.clinicaldata.research;

import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads only the pre-built aggregate views; callers never provide SQL or relation names. */
public final class JdbcResearchAggregateRepository implements ResearchAggregateRepository {
  private static final Map<ResearchView, ViewDefinition> VIEWS =
      Map.of(
          ResearchView.CONDITION_COUNTS,
              new ViewDefinition(
                  "clinical_research_condition_counts", List.of("code_system", "code", "status")),
          ResearchView.OBSERVATION_COUNTS,
              new ViewDefinition(
                  "clinical_research_observation_counts",
                  List.of("code_system", "code", "unit", "observation_year")),
          ResearchView.ENCOUNTER_COUNTS,
              new ViewDefinition(
                  "clinical_research_encounter_counts",
                  List.of("type_system", "type_code", "start_year")));

  private final NamedParameterJdbcTemplate jdbc;
  private final ClinicalQueryProperties properties;

  public JdbcResearchAggregateRepository(
      final NamedParameterJdbcTemplate jdbc, final ClinicalQueryProperties properties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public List<ResearchAggregateRow> find(final ResearchViewQuery query) {
    Objects.requireNonNull(query, "query");
    final ViewDefinition definition = VIEWS.get(query.view());
    if (definition == null || !properties.allowedViews().contains(definition.viewName())) {
      throw new ResearchQueryAccessDeniedException("research view is not allow-listed");
    }
    final MapSqlParameterSource parameters = new MapSqlParameterSource();
    final List<String> predicates = new ArrayList<>();
    for (final Map.Entry<String, String> filter : query.filters().entrySet()) {
      if (!definition.dimensions().contains(filter.getKey())) {
        throw new ResearchQueryAccessDeniedException("research filter is not allow-listed");
      }
      if (filter.getValue() == null || filter.getValue().isBlank()) {
        throw new IllegalArgumentException("research filter values must not be blank");
      }
      final String parameterName = "filter_" + filter.getKey();
      predicates.add(filter.getKey() + " = :" + parameterName);
      parameters.addValue(parameterName, filter.getValue());
    }
    parameters.addValue("max_rows", properties.maxRows());
    final String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    final String sql =
        "SELECT "
            + String.join(", ", definition.dimensions())
            + ", patient_count FROM "
            + definition.viewName()
            + where
            + " ORDER BY patient_count DESC LIMIT :max_rows";
    return jdbc.query(
        sql,
        parameters,
        (resultSet, rowNumber) -> {
          final Map<String, String> dimensions = new LinkedHashMap<>();
          for (final String dimension : definition.dimensions()) {
            dimensions.put(dimension, resultSet.getString(dimension));
          }
          return new ResearchAggregateRow(dimensions, resultSet.getLong("patient_count"));
        });
  }

  private record ViewDefinition(String viewName, List<String> dimensions) {
    private ViewDefinition {
      dimensions = List.copyOf(dimensions);
    }
  }
}
