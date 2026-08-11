package com.medassist.ingestion.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL cache for derived context prefixes. Source chunk text is never accepted or stored. */
@Repository
public class JdbcContextCache implements ContextCache {
  private static final String GET_SQL =
      """
      SELECT context_text, generation_status
      FROM chunk_context
      WHERE document_version_id = :documentVersionId
        AND chunking_strategy_id = :chunkingStrategyId
        AND chunk_ordinal = :chunkOrdinal
        AND mode = :mode
        AND prompt_version = :promptVersion
      """;

  private static final String PUT_SQL =
      """
      INSERT INTO chunk_context(
        document_version_id, chunking_strategy_id, chunk_ordinal, mode, prompt_version,
        context_text, generation_status)
      VALUES (
        :documentVersionId, :chunkingStrategyId, :chunkOrdinal, :mode, :promptVersion,
        :contextPrefix, :generationStatus)
      ON CONFLICT (document_version_id, chunking_strategy_id, chunk_ordinal, mode, prompt_version)
      DO UPDATE SET context_text = chunk_context.context_text
        WHERE chunk_context.context_text = EXCLUDED.context_text
          AND chunk_context.generation_status = EXCLUDED.generation_status
      RETURNING context_text, generation_status
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcContextCache(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<ContextCacheEntry> get(final ContextCacheKey key) {
    Objects.requireNonNull(key, "key");
    try {
      final List<ContextCacheEntry> values = jdbc.query(GET_SQL, parameters(key), rowMapper());
      if (values.size() > 1) {
        throw new ContextCacheException("context cache identity is not unique");
      }
      return values.stream().findFirst();
    } catch (final ContextCacheException exception) {
      throw exception;
    } catch (final DataAccessException exception) {
      throw new ContextCacheException("context cache read failed", exception);
    }
  }

  @Override
  public void put(final ContextCacheKey key, final ContextCacheEntry entry) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(entry, "entry");
    try {
      final MapSqlParameterSource parameters = parameters(key);
      parameters.addValue("contextPrefix", entry.contextPrefix());
      parameters.addValue("generationStatus", entry.generationStatus().name());
      final List<ContextCacheEntry> values = jdbc.query(PUT_SQL, parameters, rowMapper());
      if (values.size() != 1 || !entry.equals(values.getFirst())) {
        throw new ContextCacheConflictException("context cache content conflict");
      }
    } catch (final ContextCacheException exception) {
      throw exception;
    } catch (final DataAccessException exception) {
      throw new ContextCacheException("context cache write failed", exception);
    }
  }

  private static RowMapper<ContextCacheEntry> rowMapper() {
    return (resultSet, rowNumber) ->
        new ContextCacheEntry(
            resultSet.getString("context_text"),
            ContextCacheGenerationStatus.valueOf(resultSet.getString("generation_status")));
  }

  private static MapSqlParameterSource parameters(final ContextCacheKey key) {
    return new MapSqlParameterSource()
        .addValue("documentVersionId", key.documentVersionId())
        .addValue("chunkingStrategyId", key.chunkingStrategyId())
        .addValue("chunkOrdinal", key.chunkOrdinal())
        .addValue("mode", key.mode().name())
        .addValue("promptVersion", key.promptVersion());
  }
}
