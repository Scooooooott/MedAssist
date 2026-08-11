package com.medassist.retrieval.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class EvaluationTrendRepositoryTest {
  @Test
  void queryUsesOnlySelectWithBoundFiltersAndBoundedLimit() {
    final NamedParameterJdbcTemplate jdbc =
        org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            any(String.class),
            any(MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<EvaluationRunView>>any()))
        .thenReturn(List.of());
    final EvaluationTrendRepository repository =
        new EvaluationTrendRepository(jdbc, new ObjectMapper());

    repository.find(new EvaluationTrendQuery("golden-v2", "bge-m3", 20));

    final var invocation =
        org.mockito.Mockito.mockingDetails(jdbc).getInvocations().iterator().next();
    final String sql = String.valueOf(invocation.<Object>getArgument(0));
    assertTrue(sql.stripLeading().startsWith("SELECT"));
    assertTrue(sql.contains("eval_set_version = :evalSetVersion"));
    assertTrue(sql.contains("model_name = :modelName"));
    assertTrue(sql.contains("ORDER BY created_at DESC"));
    assertTrue(sql.contains("LIMIT :limit"));
    assertFalse(sql.toUpperCase().matches("(?s).*\\b(CREATE|ALTER|DROP|TRUNCATE)\\s+.*"));
    assertFalse(sql.contains("question"));
    assertFalse(sql.contains("answer"));
    verify(jdbc)
        .query(
            contains("evaluation_run"),
            any(MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<EvaluationRunView>>any());
  }

  @Test
  void mapsOnlyAggregateMetricLeavesAndDropsMetadata() throws Exception {
    final NamedParameterJdbcTemplate jdbc =
        org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            any(String.class),
            any(MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<EvaluationRunView>>any()))
        .thenReturn(List.of());
    final EvaluationTrendRepository repository =
        new EvaluationTrendRepository(jdbc, new ObjectMapper());
    repository.find(new EvaluationTrendQuery(null, null, 20));
    final org.mockito.ArgumentCaptor<RowMapper<EvaluationRunView>> mapperCaptor =
        org.mockito.ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc).query(any(String.class), any(MapSqlParameterSource.class), mapperCaptor.capture());
    final ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
    when(row.getObject(eq("id"), eq(UUID.class))).thenReturn(UUID.randomUUID());
    when(row.getString("eval_set_version")).thenReturn("golden-v2");
    when(row.getString("split")).thenReturn("dev");
    when(row.getString("code_commit")).thenReturn("abc123");
    when(row.getString("model_name")).thenReturn("bge-m3");
    when(row.getString("model_version")).thenReturn("m2");
    when(row.getString("judge_model")).thenReturn("judge-1");
    when(row.getLong("random_seed")).thenReturn(7L);
    when(row.getString("metrics"))
        .thenReturn("{\"recall_at_10\":0.8,\"ragas\":{\"status\":\"provided\",\"value\":0.9}}");
    when(row.getString("result_uri")).thenReturn("s3://report");
    when(row.getObject(eq("created_at"), eq(Instant.class))).thenReturn(Instant.EPOCH);

    final EvaluationRunView result = mapperCaptor.getValue().mapRow(row, 0);

    assertEquals(Map.of("recall_at_10", 0.8, "ragas", Map.of("value", 0.9)), result.metrics());
    assertEquals("abc123", result.triple().get("codeCommit"));
  }

  @Test
  void rejectsRawMetricValuesInsteadOfReturningThem() throws Exception {
    final NamedParameterJdbcTemplate jdbc =
        org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            any(String.class),
            any(MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<EvaluationRunView>>any()))
        .thenReturn(List.of());
    final EvaluationTrendRepository repository =
        new EvaluationTrendRepository(jdbc, new ObjectMapper());
    repository.find(new EvaluationTrendQuery(null, null, 20));
    final org.mockito.ArgumentCaptor<RowMapper<EvaluationRunView>> mapperCaptor =
        org.mockito.ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbc).query(any(String.class), any(MapSqlParameterSource.class), mapperCaptor.capture());
    final ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
    when(row.getString("metrics")).thenReturn("{\"raw_records\":[{\"question\":\"secret\"}]}");

    assertThrows(IllegalStateException.class, () -> mapperCaptor.getValue().mapRow(row, 0));
  }
}
