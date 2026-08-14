package com.medassist.retrieval.versioning;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentVersionRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public DocumentVersionRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<DocumentVersionView> history(final UUID documentId) {
    return jdbc.query(
        """
        SELECT dv.id, dv.document_id, dv.version, dv.effective_date, dv.status,
               dv.superseded_by, d.publisher
          FROM document_version dv
          JOIN document d ON d.id = dv.document_id
         WHERE dv.document_id = :documentId
         ORDER BY dv.effective_date DESC NULLS LAST, dv.version DESC, dv.id
        """,
        new MapSqlParameterSource().addValue("documentId", documentId),
        (row, rowNumber) ->
            new DocumentVersionView(
                row.getObject("id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getString("version"),
                row.getObject("effective_date", java.time.LocalDate.class),
                row.getString("status"),
                row.getObject("superseded_by", UUID.class),
                row.getString("publisher"),
                null));
  }

  public List<VersionChunk> chunks(
      final UUID documentId, final UUID versionId, final String chunkingStrategyId) {
    return jdbc.query(
        """
        SELECT c.id, c.ordinal, c.section_path, c.text
          FROM chunk c
          JOIN document_version dv ON dv.id = c.document_version_id
         WHERE dv.document_id = :documentId
           AND dv.id = :versionId
           AND c.chunking_strategy_id = :chunkingStrategyId
         ORDER BY c.ordinal
        """,
        new MapSqlParameterSource()
            .addValue("documentId", documentId)
            .addValue("versionId", versionId)
            .addValue("chunkingStrategyId", chunkingStrategyId),
        (row, rowNumber) ->
            new VersionChunk(
                row.getObject("id", UUID.class),
                row.getInt("ordinal"),
                row.getString("section_path"),
                row.getString("text")));
  }
}
