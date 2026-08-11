package com.medassist.ingestion.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcContextCacheTest {
  private static final String SOURCE_TEXT = "Patient Jane Doe has source-only clinical text.";
  private static final String CONTEXT_PREFIX = "Derived section context.";

  @Test
  void getReturnsHitUsingEveryIdentityDimension() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(SqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any()))
        .thenReturn(List.of(entry(ContextCacheGenerationStatus.SUCCEEDED)));
    final JdbcContextCache cache = new JdbcContextCache(jdbc);

    assertEquals(
        entry(ContextCacheGenerationStatus.SUCCEEDED),
        cache.get(key("structure-v1")).orElseThrow());

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc)
        .query(
            sql.capture(),
            parameters.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any());
    assertTrue(sql.getValue().contains("chunking_strategy_id = :chunkingStrategyId"));
    assertEquals("structure-v1", parameters.getValue().getValue("chunkingStrategyId"));
    assertEquals(7, parameters.getValue().getValue("chunkOrdinal"));
    assertEquals("RULE_BASED", parameters.getValue().getValue("mode"));
    assertEquals("prompt-v2", parameters.getValue().getValue("promptVersion"));
    assertNoSourceText(parameters.getValue());
  }

  @Test
  void getReturnsMiss() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(SqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any()))
        .thenReturn(List.of());

    assertTrue(new JdbcContextCache(jdbc).get(key("structure-v1")).isEmpty());
  }

  @Test
  void putIsIdempotentAndOnlyPersistsDerivedContext() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(SqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any()))
        .thenReturn(List.of(entry(ContextCacheGenerationStatus.RULE_FALLBACK)));
    final JdbcContextCache cache = new JdbcContextCache(jdbc);

    cache.put(key("semantic-v1"), entry(ContextCacheGenerationStatus.RULE_FALLBACK));

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc)
        .query(
            sql.capture(),
            parameters.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any());
    assertTrue(sql.getValue().contains("ON CONFLICT"));
    assertTrue(sql.getValue().contains("chunk_context.context_text = EXCLUDED.context_text"));
    assertTrue(sql.getValue().contains("RETURNING context_text, generation_status"));
    assertEquals(CONTEXT_PREFIX, parameters.getValue().getValue("contextPrefix"));
    assertEquals("RULE_FALLBACK", parameters.getValue().getValue("generationStatus"));
    assertNoSourceText(parameters.getValue());
  }

  @Test
  void conflictingPutDoesNotOverwriteAndRaisesSafeException() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(SqlParameterSource.class),
            org.mockito.ArgumentMatchers.<RowMapper<ContextCacheEntry>>any()))
        .thenReturn(List.of());
    final JdbcContextCache cache = new JdbcContextCache(jdbc);

    final ContextCacheConflictException exception =
        assertThrows(
            ContextCacheConflictException.class,
            () -> cache.put(key("structure-v1"), entry(ContextCacheGenerationStatus.SUCCEEDED)));

    assertEquals("context cache content conflict", exception.getMessage());
    assertFalse(exception.getMessage().contains(CONTEXT_PREFIX));
    assertFalse(exception.getMessage().contains(SOURCE_TEXT));
  }

  private static ContextCacheKey key(final String strategy) {
    return new ContextCacheKey(
        UUID.fromString("00000000-0000-0000-0000-000000000123"),
        strategy,
        7,
        ContextualRetrievalMode.RULE_BASED,
        "prompt-v2");
  }

  private static ContextCacheEntry entry(final ContextCacheGenerationStatus status) {
    return new ContextCacheEntry(CONTEXT_PREFIX, status);
  }

  private static void assertNoSourceText(final SqlParameterSource parameters) {
    final MapSqlParameterSource source = (MapSqlParameterSource) parameters;
    for (final String name : source.getValues().keySet()) {
      final Object value = source.getValue(name);
      assertFalse(SOURCE_TEXT.equals(value));
    }
  }
}
