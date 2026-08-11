package com.medassist.retrieval.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLexicalSearchRepository implements LexicalSearchRepository {
  private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {};
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final boolean stemmingEnabled;

  public JdbcLexicalSearchRepository(
      final NamedParameterJdbcTemplate jdbc,
      final ObjectMapper objectMapper,
      @Value("${medassist.retrieval.lexical.stemming-enabled:true}")
          final boolean stemmingEnabled) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.stemmingEnabled = stemmingEnabled;
  }

  @Override
  public List<RankedChunk> search(final SearchQuery query) {
    final String vectorColumn = stemmingEnabled ? "lexical_search" : "lexical_search_unstemmed";
    final String textConfiguration = stemmingEnabled ? "english" : "simple";
    final StringBuilder sql =
        new StringBuilder(
            """
            SELECT c.id AS chunk_id, c.document_version_id, c.ordinal, c.section_path,
                   c.text, c.token_count, c.source_char_start, c.source_char_end,
                   ts_rank_cd(c.%s, websearch_to_tsquery('%s', :query)) AS score,
                   d.doc_type, d.publisher, d.title, dv.version, dv.effective_date,
                   dv.status AS document_status,
                   CASE WHEN dv.effective_date IS NULL THEN false
                        ELSE dv.effective_date < current_date - make_interval(years => :stalenessYears)
                   END AS stale,
                   c.metadata
              FROM chunk c
              JOIN document_version dv ON dv.id = c.document_version_id
              JOIN document d ON d.id = dv.document_id
             WHERE c.%s @@ websearch_to_tsquery('%s', :query)
               AND dv.status <> 'WITHDRAWN'
               AND c.phi_scan_status = 'CLEAN'
               AND c.chunking_strategy_id = :chunkingStrategyId
            """
                .formatted(vectorColumn, textConfiguration, vectorColumn, textConfiguration));
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("query", query.query())
            .addValue("topN", query.candidateTopN())
            .addValue("stalenessYears", query.stalenessYears())
            .addValue("chunkingStrategyId", query.chunkingStrategyId());
    if (query.includeSuperseded()) {
      sql.append(" AND dv.status IN ('ACTIVE', 'SUPERSEDED')");
    } else {
      sql.append(" AND dv.status = 'ACTIVE'");
    }
    appendFilters(sql, parameters, query.filters());
    sql.append(" ORDER BY score DESC, c.id ASC LIMIT :topN");
    return jdbc.query(
        sql.toString(),
        parameters,
        (row, rowNumber) -> {
          final RetrievedChunk chunk = mapRow(row, rowNumber);
          return new RankedChunk(chunk, rowNumber + 1, row.getDouble("score"));
        });
  }

  private void appendFilters(
      final StringBuilder sql,
      final MapSqlParameterSource parameters,
      final RetrievalFilters filters) {
    if (!filters.docTypes().isEmpty()) {
      sql.append(" AND d.doc_type IN (:docTypes)");
      parameters.addValue("docTypes", filters.docTypes());
    }
    if (!filters.publishers().isEmpty()) {
      sql.append(" AND d.publisher IN (:publishers)");
      parameters.addValue("publishers", filters.publishers());
    }
    if (filters.effectiveDateFrom() != null) {
      sql.append(" AND dv.effective_date >= :effectiveDateFrom");
      parameters.addValue("effectiveDateFrom", filters.effectiveDateFrom());
    }
    if (filters.effectiveDateTo() != null) {
      sql.append(" AND dv.effective_date <= :effectiveDateTo");
      parameters.addValue("effectiveDateTo", filters.effectiveDateTo());
    }
    if (!filters.sectionTypes().isEmpty()) {
      sql.append(" AND c.metadata ->> 'section_type' IN (:sectionTypes)");
      parameters.addValue("sectionTypes", filters.sectionTypes());
    }
  }

  private RetrievedChunk mapRow(final ResultSet row, final int rowNumber) throws SQLException {
    final double score = row.getDouble("score");
    return new RetrievedChunk(
        row.getObject("chunk_id", UUID.class),
        row.getObject("document_version_id", UUID.class),
        row.getInt("ordinal"),
        row.getString("section_path"),
        row.getString("text"),
        row.getInt("token_count"),
        row.getLong("source_char_start"),
        row.getLong("source_char_end"),
        score,
        "POSTGRES_FTS",
        "TS_RANK_CD",
        row.getString("doc_type"),
        row.getString("publisher"),
        row.getString("title"),
        row.getString("version"),
        row.getObject("effective_date", LocalDate.class),
        row.getString("document_status"),
        row.getBoolean("stale"),
        null,
        rowNumber + 1,
        null,
        score,
        score,
        parseMetadata(row.getString("metadata")));
  }

  private Map<String, String> parseMetadata(final String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(raw, METADATA_TYPE);
    } catch (final Exception ignored) {
      return Map.of();
    }
  }
}
