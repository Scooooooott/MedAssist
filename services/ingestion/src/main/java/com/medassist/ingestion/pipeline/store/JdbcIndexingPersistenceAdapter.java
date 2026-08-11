package com.medassist.ingestion.pipeline.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.IndexableChunk;
import com.medassist.ingestion.pipeline.index.IndexableEmbedding;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import com.medassist.ingestion.versioning.VersionChainStatus;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for one atomic, already-deidentified indexing result.
 *
 * <p>Conflict clauses intentionally verify existing rows instead of replacing them. This keeps
 * another chunking, context, model, or dimension experiment intact.
 */
@Repository
public class JdbcIndexingPersistenceAdapter implements IndexingPersistencePort {
  private static final Set<Integer> SUPPORTED_DIMENSIONS = Set.of(768, 1024, 1536);
  private static final String VERSION_REVIEW_FIELDS_KEY = "version_metadata_review_fields";
  private static final Set<String> VERSION_REVIEW_FIELDS =
      Set.of("effective_date", "publisher", "version");

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionOperations transactions;
  private final ObjectMapper objectMapper;

  @Autowired
  public JdbcIndexingPersistenceAdapter(
      final NamedParameterJdbcTemplate jdbc,
      final PlatformTransactionManager transactionManager,
      final ObjectMapper objectMapper) {
    this(jdbc, new TransactionTemplate(transactionManager), objectMapper);
  }

  JdbcIndexingPersistenceAdapter(
      final NamedParameterJdbcTemplate jdbc,
      final TransactionOperations transactions,
      final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
  }

  @Override
  public IndexingPersistenceResult persist(final IndexingPersistenceRequest request) {
    validate(request);
    try {
      final IndexingPersistenceResult result =
          transactions.execute(status -> persistInTransaction(request));
      if (result == null) {
        throw new IndexingPersistenceException("index transaction returned no result");
      }
      return result;
    } catch (final IndexingPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new IndexingPersistenceException(
          "index transaction failed; document was not published", exception);
    }
  }

  private IndexingPersistenceResult persistInTransaction(final IndexingPersistenceRequest request) {
    final IndexingResult result = request.result();
    final UUID documentId = upsertDocument(request.identity());
    if (!documentId.equals(request.identity().logicalDocumentId())) {
      throw new IndexingPersistenceException("logical document identity conflict");
    }
    final UUID versionId = upsertDocumentVersion(documentId, request);
    if (!versionId.equals(request.version().documentVersionId())) {
      throw new IndexingPersistenceException("document version identity conflict");
    }
    persistPhiDetectionLog(request);
    if (request.version().status() == VersionChainStatus.UNKNOWN) {
      enqueueDocumentMetadataReview(request.version());
    } else {
      reconcileVersionChain(documentId);
    }

    for (final IndexableChunk chunk : result.chunks()) {
      insertChunk(request, chunk);
      if (chunk.phiScanStatus() != PhiScanStatus.CLEAN) {
        enqueueChunkPhiReview(chunk);
      }
    }
    for (final IndexableEmbedding embedding : result.embeddings()) {
      insertEmbedding(request.contextualMode(), embedding);
    }
    return new IndexingPersistenceResult(result.chunks().size(), result.embeddings().size());
  }

  private void persistPhiDetectionLog(final IndexingPersistenceRequest request) {
    for (final Map.Entry<String, Integer> entry :
        request.deidentificationPhiTypeCounts().entrySet()) {
      final int rows =
          jdbc.update(
              """
              INSERT INTO phi_detection_log(
                document_version_id, entity_type, entity_count, recognizer, policy_version)
              VALUES (:documentVersionId, :entityType, :entityCount, 'deid-svc', :policyVersion)
              ON CONFLICT (document_version_id, entity_type, recognizer, policy_version)
              DO UPDATE SET entity_count = phi_detection_log.entity_count
                WHERE phi_detection_log.entity_count = EXCLUDED.entity_count
              """,
              new MapSqlParameterSource()
                  .addValue("documentVersionId", request.version().documentVersionId())
                  .addValue("entityType", entry.getKey())
                  .addValue("entityCount", entry.getValue())
                  .addValue(
                      "policyVersion",
                      request.result().document().deidentificationPolicyVersion()));
      requireOneRow(rows, "PHI detection aggregate conflicts with existing data");
    }
  }

  private void enqueueDocumentMetadataReview(final DocumentVersionMetadata version) {
    final List<String> fields = reviewFields(version);
    final String reasonCode = reviewReasonCode(fields);
    final int rows =
        jdbc.update(
            """
            INSERT INTO document_metadata_review(document_version_id, missing_fields, reason_code)
            VALUES (:documentVersionId, :missingFields, :reasonCode)
            ON CONFLICT (document_version_id) DO UPDATE
              SET document_version_id = document_metadata_review.document_version_id
              WHERE document_metadata_review.status = 'PENDING'
                AND document_metadata_review.missing_fields = EXCLUDED.missing_fields
                AND document_metadata_review.reason_code = EXCLUDED.reason_code
            """,
            new MapSqlParameterSource()
                .addValue("documentVersionId", version.documentVersionId())
                .addValue("missingFields", fields.toArray(String[]::new))
                .addValue("reasonCode", reasonCode));
    requireOneRow(rows, "document metadata review conflicts with existing data");
  }

  private void reconcileVersionChain(final UUID documentId) {
    final List<UUID> orderedVersionIds =
        jdbc.queryForList(
            """
            SELECT id
              FROM document_version
             WHERE document_id = :documentId
               AND status NOT IN ('UNKNOWN', 'WITHDRAWN')
               AND effective_date IS NOT NULL
             ORDER BY effective_date DESC, version DESC, retrieved_at DESC, id ASC
            """,
            new MapSqlParameterSource("documentId", documentId),
            UUID.class);
    if (orderedVersionIds == null || orderedVersionIds.isEmpty()) {
      throw new IndexingPersistenceException("version chain contains no confirmed version");
    }
    for (int index = 0; index < orderedVersionIds.size(); index++) {
      final UUID versionId = orderedVersionIds.get(index);
      final VersionChainStatus status =
          index == 0 ? VersionChainStatus.ACTIVE : VersionChainStatus.SUPERSEDED;
      final UUID supersededBy = index == 0 ? null : orderedVersionIds.get(index - 1);
      final int rows =
          jdbc.update(
              """
              UPDATE document_version
                 SET status = :status, superseded_by = :supersededBy
               WHERE id = :versionId
                 AND document_id = :documentId
                 AND status <> 'WITHDRAWN'
              """,
              new MapSqlParameterSource()
                  .addValue("status", status.name())
                  .addValue("supersededBy", supersededBy)
                  .addValue("versionId", versionId)
                  .addValue("documentId", documentId));
      requireOneRow(rows, "version chain changed during publication");
    }
  }

  private void enqueueChunkPhiReview(final IndexableChunk chunk) {
    final int rows =
        jdbc.update(
            """
            INSERT INTO chunk_phi_review(chunk_id, phi_scan_status, phi_entity_types)
            VALUES (:chunkId, :phiScanStatus, :phiEntityTypes)
            ON CONFLICT (chunk_id) DO UPDATE
              SET chunk_id = chunk_phi_review.chunk_id
              WHERE chunk_phi_review.status = 'PENDING'
                AND chunk_phi_review.phi_scan_status = EXCLUDED.phi_scan_status
                AND chunk_phi_review.phi_entity_types = EXCLUDED.phi_entity_types
            """,
            new MapSqlParameterSource()
                .addValue("chunkId", chunk.id())
                .addValue("phiScanStatus", chunk.phiScanStatus().name())
                .addValue("phiEntityTypes", chunk.phiEntityTypes().toArray(String[]::new)));
    requireOneRow(rows, "chunk PHI review conflicts with existing data");
  }

  private static List<String> reviewFields(final DocumentVersionMetadata version) {
    final String encoded = version.metadata().get(VERSION_REVIEW_FIELDS_KEY);
    if (encoded == null || encoded.isBlank()) {
      throw new IndexingPersistenceException("unknown version has no review fields");
    }
    final List<String> fields =
        Arrays.stream(encoded.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    if (fields.isEmpty() || !VERSION_REVIEW_FIELDS.containsAll(fields)) {
      throw new IndexingPersistenceException("unknown version review fields are invalid");
    }
    return fields;
  }

  private static String reviewReasonCode(final List<String> fields) {
    if (fields.contains("effective_date")) {
      return "MISSING_EFFECTIVE_DATE";
    }
    if (fields.contains("version")) {
      return "MISSING_VERSION";
    }
    return "MISSING_PUBLISHER";
  }

  private UUID upsertDocument(final DocumentIdentity identity) {
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", identity.logicalDocumentId())
            .addValue("sourceSystem", identity.sourceSystem())
            .addValue("sourceUri", identity.sourceUri())
            .addValue("docType", identity.docType())
            .addValue("publisher", identity.publisher())
            .addValue("title", identity.title());
    return jdbc.queryForObject(
        """
        INSERT INTO document(id, source_system, source_uri, doc_type, publisher, title)
        VALUES (:id, :sourceSystem, :sourceUri, :docType, :publisher, :title)
        ON CONFLICT (source_system, source_uri) DO UPDATE
          SET publisher = EXCLUDED.publisher, title = EXCLUDED.title, doc_type = EXCLUDED.doc_type
        RETURNING id
        """,
        parameters,
        UUID.class);
  }

  private UUID upsertDocumentVersion(
      final UUID documentId, final IndexingPersistenceRequest request) {
    final DocumentVersionMetadata version = request.version();
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", version.documentVersionId())
            .addValue("documentId", documentId)
            .addValue("version", version.version())
            .addValue("contentHash", version.contentHash())
            .addValue("effectiveDate", version.effectiveDate())
            .addValue("retrievedAt", version.retrievedAt())
            .addValue("status", version.status().name())
            .addValue("supersededBy", version.supersededBy())
            .addValue("storageUri", version.storageUri())
            .addValue("contentDomain", version.contentDomain())
            .addValue("metadata", json(combinedMetadata(request)));
    return jdbc.queryForObject(
        """
        INSERT INTO document_version(
          id, document_id, version, content_hash, effective_date, retrieved_at, status,
          superseded_by, storage_uri, content_domain, metadata)
        VALUES (
          :id, :documentId, :version, :contentHash, :effectiveDate, :retrievedAt, :status,
          :supersededBy, :storageUri, :contentDomain, CAST(:metadata AS jsonb))
        ON CONFLICT (document_id, content_hash) DO UPDATE SET
          version = EXCLUDED.version,
          effective_date = EXCLUDED.effective_date,
          retrieved_at = EXCLUDED.retrieved_at,
          status = EXCLUDED.status,
          superseded_by = EXCLUDED.superseded_by,
          storage_uri = EXCLUDED.storage_uri,
          content_domain = EXCLUDED.content_domain,
          metadata = EXCLUDED.metadata
        RETURNING id
        """,
        parameters,
        UUID.class);
  }

  private static Map<String, String> combinedMetadata(final IndexingPersistenceRequest request) {
    final Map<String, String> metadata =
        new LinkedHashMap<>(request.result().document().metadata());
    metadata.putAll(request.version().metadata());
    return metadata;
  }

  private void insertChunk(final IndexingPersistenceRequest request, final IndexableChunk chunk) {
    final Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("breadcrumb", chunk.breadcrumb());
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", chunk.id())
            .addValue("documentVersionId", chunk.documentVersionId())
            .addValue("ordinal", chunk.ordinal())
            .addValue("sectionPath", chunk.sectionPath())
            .addValue("text", chunk.text())
            .addValue("tokenCount", chunk.tokenCount())
            .addValue("contentDomain", request.version().contentDomain())
            .addValue("sourceStart", chunk.sourceRange().start())
            .addValue("sourceEnd", chunk.sourceRange().end())
            .addValue("phiScanStatus", chunk.phiScanStatus().name())
            .addValue("phiEntityTypes", chunk.phiEntityTypes().toArray(String[]::new))
            .addValue("metadata", json(metadata))
            .addValue("contextPrefix", blankToNull(chunk.contextPrefix()))
            .addValue("contextualMode", request.contextualMode().name())
            .addValue("contextPromptVersion", request.contextPromptVersion())
            .addValue("chunkingStrategyId", chunk.chunkingStrategyId());
    final UUID persistedId =
        jdbc
            .query(
                """
                INSERT INTO chunk(
                  id, document_version_id, ordinal, section_path, text, token_count, content_domain,
                  source_char_start, source_char_end, phi_scan_status, phi_entity_types, metadata, context_prefix,
                  contextual_mode, context_prompt_version, chunking_strategy_id)
                VALUES (
                  :id, :documentVersionId, :ordinal, :sectionPath, :text, :tokenCount, :contentDomain,
                  :sourceStart, :sourceEnd, :phiScanStatus, :phiEntityTypes, CAST(:metadata AS jsonb), :contextPrefix,
                  :contextualMode, :contextPromptVersion, :chunkingStrategyId)
                ON CONFLICT (document_version_id, chunking_strategy_id, ordinal) DO UPDATE SET id = chunk.id
                  WHERE chunk.text = EXCLUDED.text
                    AND chunk.section_path = EXCLUDED.section_path
                    AND chunk.token_count = EXCLUDED.token_count
                    AND chunk.content_domain = EXCLUDED.content_domain
                    AND chunk.source_char_start = EXCLUDED.source_char_start
                    AND chunk.source_char_end = EXCLUDED.source_char_end
                    AND chunk.phi_scan_status = EXCLUDED.phi_scan_status
                    AND chunk.phi_entity_types = EXCLUDED.phi_entity_types
                    AND chunk.metadata = EXCLUDED.metadata
                    AND chunk.context_prefix IS NOT DISTINCT FROM EXCLUDED.context_prefix
                    AND chunk.contextual_mode = EXCLUDED.contextual_mode
                    AND chunk.context_prompt_version = EXCLUDED.context_prompt_version
                RETURNING id
                """,
                parameters,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new IndexingPersistenceException("conflicting chunk was not overwritten"));
    if (!persistedId.equals(chunk.id())) {
      throw new IndexingPersistenceException("chunk identity conflict");
    }
  }

  private void insertEmbedding(
      final ContextualRetrievalMode contextualMode, final IndexableEmbedding embedding) {
    final String table = embeddingTable(embedding.dimension());
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("chunkId", embedding.chunkId())
            .addValue("modelName", embedding.modelName())
            .addValue("modelVersion", embedding.modelVersion())
            .addValue("contextualMode", contextualMode.name())
            .addValue("embedding", vectorLiteral(embedding.values()));
    final UUID persistedId =
        jdbc
            .query(
                """
                INSERT INTO %s(chunk_id, model_name, model_version, contextual_mode, embedding)
                VALUES (:chunkId, :modelName, :modelVersion, :contextualMode, CAST(:embedding AS vector))
                ON CONFLICT (chunk_id, model_name, model_version, contextual_mode)
                  DO UPDATE SET chunk_id = %s.chunk_id
                  WHERE %s.embedding = EXCLUDED.embedding
                RETURNING chunk_id
                """
                    .formatted(table, table, table),
                parameters,
                (resultSet, rowNumber) -> resultSet.getObject("chunk_id", UUID.class))
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IndexingPersistenceException("conflicting embedding was not overwritten"));
    if (!persistedId.equals(embedding.chunkId())) {
      throw new IndexingPersistenceException("embedding identity conflict");
    }
  }

  private static String embeddingTable(final int dimension) {
    if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
      throw new IndexingPersistenceException("unsupported embedding dimension");
    }
    return switch (dimension) {
      case 768 -> "chunk_embedding_768";
      case 1024 -> "chunk_embedding";
      case 1536 -> "chunk_embedding_1536";
      default -> throw new IndexingPersistenceException("unsupported embedding dimension");
    };
  }

  private void validate(final IndexingPersistenceRequest request) {
    if (request == null) {
      throw new IndexingPersistenceException("persistence request must not be null");
    }
    final IndexingResult result = request.result();
    if (request.version().status() != VersionChainStatus.UNKNOWN
        && request.version().effectiveDate() == null) {
      throw new IndexingPersistenceException("confirmed version requires an effective date");
    }
    if (request.version().status() == VersionChainStatus.UNKNOWN) {
      reviewFields(request.version());
    }
    if (result.chunks().isEmpty()) {
      throw new IndexingPersistenceException("persistence request contains no chunks");
    }
    final Set<UUID> chunkIds = new HashSet<>();
    final Set<Integer> ordinals = new HashSet<>();
    String strategy = null;
    for (final IndexableChunk chunk : result.chunks()) {
      if (!request.version().documentVersionId().equals(chunk.documentVersionId())
          || !chunkIds.add(chunk.id())
          || !ordinals.add(chunk.ordinal())) {
        throw new IndexingPersistenceException("chunk identity or ordinal is inconsistent");
      }
      if (strategy == null) {
        strategy = chunk.chunkingStrategyId();
      } else if (!strategy.equals(chunk.chunkingStrategyId())) {
        throw new IndexingPersistenceException(
            "one persistence request must use one chunking strategy");
      }
    }
    final Set<UUID> embeddingChunkIds = new HashSet<>();
    String modelName = null;
    String modelVersion = null;
    Integer dimension = null;
    for (final IndexableEmbedding embedding : result.embeddings()) {
      if (!chunkIds.contains(embedding.chunkId()) || !embeddingChunkIds.add(embedding.chunkId())) {
        throw new IndexingPersistenceException("embedding identity or count is inconsistent");
      }
      embeddingTable(embedding.dimension());
      if (modelName == null) {
        modelName = embedding.modelName();
        modelVersion = embedding.modelVersion();
        dimension = embedding.dimension();
      } else if (!modelName.equals(embedding.modelName())
          || !modelVersion.equals(embedding.modelVersion())
          || dimension != embedding.dimension()) {
        throw new IndexingPersistenceException(
            "one persistence request must use one model identity");
      }
    }
    final Set<UUID> expectedEmbeddingChunkIds =
        result.chunks().stream()
            .filter(
                chunk ->
                    chunk.phiScanStatus()
                        == com.medassist.ingestion.pipeline.index.PhiScanStatus.CLEAN)
            .map(IndexableChunk::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!embeddingChunkIds.equals(expectedEmbeddingChunkIds)) {
      throw new IndexingPersistenceException("only clean chunks must have exactly one embedding");
    }
  }

  private static void requireOneRow(final int rows, final String message) {
    if (rows != 1) {
      throw new IndexingPersistenceException(message);
    }
  }

  private String json(final Map<String, String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (final JsonProcessingException exception) {
      throw new IndexingPersistenceException("metadata serialization failed", exception);
    }
  }

  private static String blankToNull(final String value) {
    return value.isBlank() ? null : value;
  }

  private static String vectorLiteral(final List<Float> values) {
    return values.stream()
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }
}
