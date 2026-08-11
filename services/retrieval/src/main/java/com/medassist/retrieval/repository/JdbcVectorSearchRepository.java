package com.medassist.retrieval.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchQuery;
import com.medassist.retrieval.model.QueryEmbedding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVectorSearchRepository implements VectorSearchRepository {
  private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {};
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public JdbcVectorSearchRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<RetrievedChunk> search(final SearchQuery query, final QueryEmbedding embedding) {
    if (!"COSINE".equalsIgnoreCase(query.distanceMetric())) {
      throw new IllegalArgumentException("only COSINE distance is supported in M1");
    }
    final String embeddingTable = embeddingTable(embedding.vector().size());
    final StringBuilder sql =
        new StringBuilder(
            """
            SELECT c.id AS chunk_id, c.document_version_id, c.ordinal, c.section_path,
                   c.text, c.token_count, c.source_char_start, c.source_char_end,
                   1 - (ce.embedding <=> CAST(:embedding AS vector)) AS score,
                   d.doc_type, d.publisher, d.title, dv.version, dv.effective_date,
                   dv.status AS document_status,
                   CASE WHEN dv.effective_date IS NULL THEN false
                        ELSE dv.effective_date < current_date - make_interval(years => :stalenessYears)
                   END AS stale,
                   c.metadata
              FROM %s ce
              JOIN chunk c ON c.id = ce.chunk_id
              JOIN document_version dv ON dv.id = c.document_version_id
              JOIN document d ON d.id = dv.document_id
             WHERE ce.model_name = :modelName
               AND ce.model_version = :modelVersion
               AND dv.status <> 'WITHDRAWN'
               AND c.phi_scan_status = 'CLEAN'
               AND c.chunking_strategy_id = :chunkingStrategyId
               AND ce.contextual_mode = :contextualMode
            """
                .formatted(embeddingTable));
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("embedding", vectorLiteral(embedding.vector()))
            .addValue("modelName", query.modelName())
            .addValue("modelVersion", query.modelVersion())
            .addValue("topK", query.candidateTopN())
            .addValue("stalenessYears", query.stalenessYears())
            .addValue("chunkingStrategyId", query.chunkingStrategyId())
            .addValue("contextualMode", query.contextualRetrievalMode().name());
    if (query.includeSuperseded()) {
      sql.append(" AND dv.status IN ('ACTIVE', 'SUPERSEDED')");
    } else {
      sql.append(" AND dv.status = 'ACTIVE'");
    }
    appendFilters(sql, params, query.filters());
    sql.append(" ORDER BY ce.embedding <=> CAST(:embedding AS vector) ASC LIMIT :topK");
    return jdbc.query(sql.toString(), params, this::mapRow);
  }

  private String embeddingTable(final int dimension) {
    return switch (dimension) {
      case 768 -> "chunk_embedding_768";
      case 1024 -> "chunk_embedding";
      case 1536 -> "chunk_embedding_1536";
      default ->
          throw new IllegalArgumentException("unsupported embedding dimension: " + dimension);
    };
  }

  private void appendFilters(
      final StringBuilder sql, final MapSqlParameterSource params, final RetrievalFilters filters) {
    if (!filters.docTypes().isEmpty()) {
      sql.append(" AND d.doc_type IN (:docTypes)");
      params.addValue("docTypes", filters.docTypes());
    }
    if (!filters.publishers().isEmpty()) {
      sql.append(" AND d.publisher IN (:publishers)");
      params.addValue("publishers", filters.publishers());
    }
    if (filters.effectiveDateFrom() != null) {
      sql.append(" AND dv.effective_date >= :effectiveDateFrom");
      params.addValue("effectiveDateFrom", filters.effectiveDateFrom());
    }
    if (filters.effectiveDateTo() != null) {
      sql.append(" AND dv.effective_date <= :effectiveDateTo");
      params.addValue("effectiveDateTo", filters.effectiveDateTo());
    }
    if (!filters.sectionTypes().isEmpty()) {
      sql.append(" AND c.metadata ->> 'section_type' IN (:sectionTypes)");
      params.addValue("sectionTypes", filters.sectionTypes());
    }
  }

  private RetrievedChunk mapRow(final ResultSet row, final int rowNumber) throws SQLException {
    return new RetrievedChunk(
        row.getObject("chunk_id", UUID.class),
        row.getObject("document_version_id", UUID.class),
        row.getInt("ordinal"),
        row.getString("section_path"),
        row.getString("text"),
        row.getInt("token_count"),
        row.getLong("source_char_start"),
        row.getLong("source_char_end"),
        row.getDouble("score"),
        "PGVECTOR_COSINE",
        "COSINE",
        row.getString("doc_type"),
        row.getString("publisher"),
        row.getString("title"),
        row.getString("version"),
        row.getObject("effective_date", LocalDate.class),
        row.getString("document_status"),
        row.getBoolean("stale"),
        rowNumber + 1,
        null,
        row.getDouble("score"),
        null,
        row.getDouble("score"),
        parseMetadata(row.getString("metadata")));
  }

  private Map<String, String> parseMetadata(final String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(raw, METADATA_TYPE);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private static String vectorLiteral(final List<Float> vector) {
    if (vector.isEmpty()) {
      throw new IllegalArgumentException("embedding vector must not be empty");
    }
    return "["
        + vector.stream()
            .map(String::valueOf)
            .reduce((left, right) -> left + "," + right)
            .orElseThrow()
        + "]";
  }
}
