package com.medassist.retrieval.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvaluationTrendRepository {
  private static final TypeReference<Map<String, Object>> METRICS_TYPE = new TypeReference<>() {};
  private static final Set<String> FORBIDDEN_METRIC_KEYS =
      Set.of("question", "answer", "prompt", "results", "raw_records");
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public EvaluationTrendRepository(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public List<EvaluationRunView> find(final EvaluationTrendQuery query) {
    final StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, eval_set_version, split, code_commit, model_name, model_version,
                   judge_model, random_seed, metrics, result_uri, created_at
              FROM evaluation_run
             WHERE 1 = 1
            """);
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource().addValue("limit", query.limit());
    if (query.evalSetVersion() != null) {
      sql.append(" AND eval_set_version = :evalSetVersion");
      parameters.addValue("evalSetVersion", query.evalSetVersion());
    }
    if (query.modelName() != null) {
      sql.append(" AND model_name = :modelName");
      parameters.addValue("modelName", query.modelName());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), parameters, (row, rowNumber) -> map(row));
  }

  private EvaluationRunView map(final java.sql.ResultSet row) throws java.sql.SQLException {
    return new EvaluationRunView(
        row.getObject("id", UUID.class),
        row.getString("eval_set_version"),
        row.getString("split"),
        row.getString("code_commit"),
        row.getString("model_name"),
        row.getString("model_version"),
        row.getString("judge_model"),
        row.getLong("random_seed"),
        readAggregateMetrics(row.getString("metrics")),
        row.getString("result_uri"),
        row.getObject("created_at", java.time.Instant.class));
  }

  private Map<String, Object> readAggregateMetrics(final String rawMetrics) {
    try {
      final JsonNode root = objectMapper.readTree(rawMetrics);
      if (root == null || !root.isObject()) {
        throw new IllegalStateException("Stored evaluation metrics are not an object");
      }
      final ObjectNode safeMetrics = objectMapper.createObjectNode();
      copyAggregateMetrics(root, safeMetrics);
      if (safeMetrics.isEmpty()) {
        throw new IllegalStateException("Stored evaluation metrics are empty");
      }
      return objectMapper.convertValue(safeMetrics, METRICS_TYPE);
    } catch (final JsonProcessingException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored evaluation metrics are invalid", exception);
    }
  }

  private void copyAggregateMetrics(final JsonNode source, final ObjectNode target) {
    final java.util.Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
    while (fields.hasNext()) {
      final Map.Entry<String, JsonNode> field = fields.next();
      if (FORBIDDEN_METRIC_KEYS.contains(field.getKey().toLowerCase())) {
        throw new IllegalStateException("Stored evaluation metrics contain raw evaluation fields");
      }
      if (field.getKey().equals("status") || field.getKey().equals("source")) {
        continue;
      }
      final JsonNode value = field.getValue();
      if (value.isNumber()) {
        target.set(field.getKey(), value);
      } else if (value.isObject()) {
        final ObjectNode nested = objectMapper.createObjectNode();
        copyAggregateMetrics(value, nested);
        if (!nested.isEmpty()) {
          target.set(field.getKey(), nested);
        }
      } else {
        throw new IllegalStateException("Stored evaluation metrics contain non-aggregate data");
      }
    }
  }
}
