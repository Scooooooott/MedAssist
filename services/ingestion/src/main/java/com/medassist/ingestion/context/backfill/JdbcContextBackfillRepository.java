package com.medassist.ingestion.context.backfill;

import com.medassist.domain.Chunk;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for incremental, context-only re-embedding of persisted chunks. */
@Repository
public class JdbcContextBackfillRepository implements ContextBackfillRepository {
  private static final Set<Integer> SUPPORTED_DIMENSIONS = Set.of(768, 1024, 1536);

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionOperations transactions;

  @Autowired
  public JdbcContextBackfillRepository(
      final NamedParameterJdbcTemplate jdbc, final PlatformTransactionManager transactionManager) {
    this(jdbc, new TransactionTemplate(transactionManager));
  }

  JdbcContextBackfillRepository(
      final NamedParameterJdbcTemplate jdbc, final TransactionOperations transactions) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public List<ContextBackfillDocument> findPending(
      final ContextualRetrievalMode mode, final String promptVersion, final int chunkLimit) {
    if (mode == null
        || mode == ContextualRetrievalMode.OFF
        || promptVersion == null
        || promptVersion.isBlank()
        || chunkLimit <= 0) {
      throw new IllegalArgumentException("context backfill query is invalid");
    }
    try {
      final List<PendingRow> rows =
          jdbc.query(
              """
              SELECT c.id AS chunk_id, c.document_version_id, c.ordinal, c.section_path,
                     c.text, c.token_count, c.source_char_start, c.source_char_end,
                     c.chunking_strategy_id,
                     COALESCE(NULLIF(c.metadata->>'breadcrumb', ''), c.section_path) AS breadcrumb,
                     d.title, d.publisher,
                     COALESCE(
                       NULLIF(dv.metadata->>'summary', ''),
                       NULLIF(LEFT(first_chunk.text, 512), ''),
                       'Summary unavailable') AS document_summary
                FROM chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                LEFT JOIN LATERAL (
                  SELECT c0.text
                    FROM chunk c0
                   WHERE c0.document_version_id = c.document_version_id
                     AND c0.chunking_strategy_id = c.chunking_strategy_id
                   ORDER BY c0.ordinal
                   LIMIT 1
                ) first_chunk ON true
               WHERE c.phi_scan_status = 'CLEAN'
                 AND (
                   c.contextual_mode <> :mode
                   OR c.context_prompt_version IS DISTINCT FROM :promptVersion
                   OR c.context_prefix IS NULL)
               ORDER BY c.document_version_id, c.chunking_strategy_id, c.ordinal
               LIMIT :chunkLimit
              """,
              new MapSqlParameterSource()
                  .addValue("mode", mode.name())
                  .addValue("promptVersion", promptVersion)
                  .addValue("chunkLimit", chunkLimit),
              (resultSet, rowNumber) ->
                  new PendingRow(
                      new ContextDocument(
                          resultSet.getObject("document_version_id", UUID.class),
                          resultSet.getString("title"),
                          resultSet.getString("publisher"),
                          normalizeSummary(resultSet.getString("document_summary"))),
                      new Chunk(
                          resultSet.getObject("chunk_id", UUID.class),
                          resultSet.getObject("document_version_id", UUID.class),
                          resultSet.getInt("ordinal"),
                          resultSet.getString("section_path"),
                          resultSet.getString("text"),
                          resultSet.getInt("token_count"),
                          new SourceRange(
                              resultSet.getLong("source_char_start"),
                              resultSet.getLong("source_char_end")),
                          Map.of(
                              "breadcrumb", resultSet.getString("breadcrumb"),
                              "chunking_strategy_id",
                                  resultSet.getString("chunking_strategy_id")))));
      return group(rows);
    } catch (final ContextBackfillPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new ContextBackfillPersistenceException("context backfill read failed", exception);
    }
  }

  @Override
  public void save(final ContextBackfillWrite write) {
    java.util.Objects.requireNonNull(write, "write");
    try {
      final Void result =
          transactions.execute(
              status -> {
                for (final ContextBackfillChunkWrite chunk : write.chunks()) {
                  persistChunk(write, chunk);
                }
                return null;
              });
      if (result != null) {
        throw new ContextBackfillPersistenceException("context backfill transaction is invalid");
      }
    } catch (final ContextBackfillPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new ContextBackfillPersistenceException("context backfill write failed", exception);
    }
  }

  @Override
  public void enqueuePhiReview(
      final UUID chunkId, final PhiScanStatus status, final Set<String> entityTypes) {
    if (chunkId == null
        || (status != PhiScanStatus.SUSPECT && status != PhiScanStatus.FAILED)
        || entityTypes == null) {
      throw new IllegalArgumentException("context PHI review request is invalid");
    }
    try {
      final int rows =
          jdbc.update(
              """
              INSERT INTO chunk_phi_review(chunk_id, phi_scan_status, phi_entity_types)
              VALUES (:chunkId, :phiScanStatus, :phiEntityTypes)
              ON CONFLICT (chunk_id) DO UPDATE
                SET phi_scan_status = EXCLUDED.phi_scan_status,
                    phi_entity_types = EXCLUDED.phi_entity_types
                WHERE chunk_phi_review.status = 'PENDING'
              """,
              new MapSqlParameterSource()
                  .addValue("chunkId", chunkId)
                  .addValue("phiScanStatus", status.name())
                  .addValue("phiEntityTypes", entityTypes.toArray(String[]::new)));
      requireOne(rows, "context PHI review queue conflict");
    } catch (final ContextBackfillPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new ContextBackfillPersistenceException("context PHI review write failed", exception);
    }
  }

  private void persistChunk(
      final ContextBackfillWrite write, final ContextBackfillChunkWrite chunk) {
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("chunkId", chunk.chunkId())
            .addValue("contextPrefix", chunk.contextPrefix())
            .addValue("mode", write.mode().name())
            .addValue("promptVersion", write.promptVersion());
    final int updated =
        jdbc.update(
            """
            UPDATE chunk
               SET context_prefix = :contextPrefix,
                   contextual_mode = :mode,
                   context_prompt_version = :promptVersion
             WHERE id = :chunkId AND phi_scan_status = 'CLEAN'
            """,
            parameters);
    requireOne(updated, "context chunk changed before backfill");

    final EmbeddingModel model = write.model();
    final String table = embeddingTable(model.dimension());
    parameters
        .addValue("modelName", model.name())
        .addValue("modelVersion", model.version())
        .addValue("embedding", vectorLiteral(chunk.embedding()));
    final List<UUID> persisted =
        jdbc.query(
            """
            INSERT INTO %s(chunk_id, model_name, model_version, contextual_mode, embedding)
            VALUES (:chunkId, :modelName, :modelVersion, :mode, CAST(:embedding AS vector))
            ON CONFLICT (chunk_id, model_name, model_version, contextual_mode)
            DO UPDATE SET chunk_id = %s.chunk_id
              WHERE %s.embedding = EXCLUDED.embedding
            RETURNING chunk_id
            """
                .formatted(table, table, table),
            parameters,
            (resultSet, rowNumber) -> resultSet.getObject("chunk_id", UUID.class));
    if (persisted.size() != 1 || !chunk.chunkId().equals(persisted.getFirst())) {
      throw new ContextBackfillPersistenceException(
          "context embedding conflicts with existing data");
    }
  }

  private static List<ContextBackfillDocument> group(final List<PendingRow> rows) {
    final Map<UUID, DocumentAccumulator> grouped = new LinkedHashMap<>();
    for (final PendingRow row : rows) {
      final DocumentAccumulator accumulator =
          grouped.computeIfAbsent(
              row.document().documentVersionId(),
              ignored -> new DocumentAccumulator(row.document()));
      if (!accumulator.document.equals(row.document())) {
        throw new ContextBackfillPersistenceException("context document metadata is inconsistent");
      }
      accumulator.chunks.add(row.chunk());
    }
    return grouped.values().stream()
        .map(value -> new ContextBackfillDocument(value.document, value.chunks))
        .toList();
  }

  private static String normalizeSummary(final String value) {
    final String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
    if (normalized.isBlank()) {
      return "Summary unavailable";
    }
    return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
  }

  private static String embeddingTable(final int dimension) {
    if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
      throw new ContextBackfillPersistenceException("unsupported context embedding dimension");
    }
    return switch (dimension) {
      case 768 -> "chunk_embedding_768";
      case 1024 -> "chunk_embedding";
      case 1536 -> "chunk_embedding_1536";
      default ->
          throw new ContextBackfillPersistenceException("unsupported context embedding dimension");
    };
  }

  private static String vectorLiteral(final List<Float> values) {
    return values.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
  }

  private static void requireOne(final int rows, final String message) {
    if (rows != 1) {
      throw new ContextBackfillPersistenceException(message);
    }
  }

  private record PendingRow(ContextDocument document, Chunk chunk) {}

  private static final class DocumentAccumulator {
    private final ContextDocument document;
    private final List<Chunk> chunks = new ArrayList<>();

    private DocumentAccumulator(final ContextDocument document) {
      this.document = document;
    }
  }
}
