package com.medassist.ingestion.batch.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.domain.DocumentIR;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for restart-safe, de-identified ingestion stage data. */
@Repository
public class JdbcDurableStageRepository implements DurableStageRepository {
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final int MAX_SAFE_REASON_LENGTH = 256;
  private static final Set<String> FORBIDDEN_JSON_FIELDS =
      Set.of(
          "raw",
          "rawcontent",
          "rawtext",
          "original",
          "originalcontent",
          "originalparserir",
          "parseddocument",
          "phioriginal",
          "phivalue",
          "patientname",
          "mrn",
          "ssn",
          "dateofbirth",
          "dob",
          "stacktrace",
          "exception");

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcDurableStageRepository(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void upsertDiscovered(final DiscoveredStageItem item) {
    if (item == null) {
      throw new StagePersistenceException("discovered stage item is required");
    }
    final MapSqlParameterSource parameters =
        identityParameters(item.ingestionRunId(), item.documentVersionId())
            .addValue("logicalDocumentId", item.logicalDocumentId())
            .addValue("sourceUri", item.sourceUri().toString())
            .addValue("sourceId", item.sourceId())
            .addValue("mimeType", item.mimeType())
            .addValue("sizeBytes", item.sizeBytes())
            .addValue("contentHash", item.contentHash())
            .addValue("previousContentHash", item.previousContentHash())
            .addValue("classification", item.classification().name())
            .addValue("objectMetadata", writeSafeJson(item.safeObjectMetadata(), "object metadata"))
            .addValue("forceReprocess", item.forceReprocess());
    final int rows =
        safeUpdate(
            """
            INSERT INTO ingestion_stage(
              ingestion_run_id, logical_document_id, document_version_id, source_uri, source_id,
              mime_type, size_bytes, content_hash, previous_content_hash, classification,
              object_metadata, force_reprocess, status)
            VALUES (
              :ingestionRunId, :logicalDocumentId, :documentVersionId, :sourceUri, :sourceId,
              :mimeType, :sizeBytes, :contentHash, :previousContentHash, :classification,
              CAST(:objectMetadata AS jsonb), :forceReprocess, 'DISCOVERED')
            ON CONFLICT (ingestion_run_id, document_version_id) DO UPDATE
              SET updated_at = ingestion_stage.updated_at
              WHERE ingestion_stage.logical_document_id = EXCLUDED.logical_document_id
                AND ingestion_stage.source_uri = EXCLUDED.source_uri
                AND ingestion_stage.source_id = EXCLUDED.source_id
                AND ingestion_stage.mime_type = EXCLUDED.mime_type
                AND ingestion_stage.size_bytes = EXCLUDED.size_bytes
                AND ingestion_stage.content_hash = EXCLUDED.content_hash
                AND ingestion_stage.previous_content_hash IS NOT DISTINCT FROM EXCLUDED.previous_content_hash
                AND ingestion_stage.classification = EXCLUDED.classification
                AND ingestion_stage.object_metadata = EXCLUDED.object_metadata
                AND ingestion_stage.force_reprocess = EXCLUDED.force_reprocess
            """,
            parameters,
            "discovered stage write failed");
    requireOneRow(rows, "discovered stage conflicts with existing state");
  }

  @Override
  public List<DurableStageItem> findByRunAndState(
      final UUID ingestionRunId, final IngestionStageStatus state) {
    requireIdentity(ingestionRunId, "ingestionRunId");
    if (state == null) {
      throw new StagePersistenceException("stage state is required");
    }
    try {
      return jdbc.query(
          """
          SELECT ingestion_run_id, logical_document_id, document_version_id, source_uri, source_id,
                 mime_type, size_bytes, content_hash, previous_content_hash, classification,
                 object_metadata::text, force_reprocess, status, deidentified_ir::text,
                 phi_type_counts::text, policy_version, processing_status,
                 indexing_result::text, quarantine_stage, error_code, safe_reason
            FROM ingestion_stage
           WHERE ingestion_run_id = :ingestionRunId AND status = :status
           ORDER BY source_id, document_version_id
          """,
          new MapSqlParameterSource("ingestionRunId", ingestionRunId)
              .addValue("status", state.name()),
          rowMapper());
    } catch (final StagePersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new StagePersistenceException("stage read failed");
    }
  }

  @Override
  public void saveDeidentified(
      final UUID ingestionRunId,
      final UUID documentVersionId,
      final IngestionStageStatus expectedState,
      final DocumentIR deidentifiedIr,
      final Map<String, Integer> phiTypeCounts,
      final String policyVersion,
      final ProcessingStatus processingStatus) {
    requireExpected(expectedState, IngestionStageStatus.DISCOVERED, "de-identification");
    if (deidentifiedIr == null) {
      throw new StagePersistenceException("de-identified IR is required");
    }
    if (phiTypeCounts == null
        || policyVersion == null
        || policyVersion.isBlank()
        || (processingStatus != ProcessingStatus.SUCCEEDED
            && processingStatus != ProcessingStatus.PARTIAL)) {
      throw new StagePersistenceException("de-identification metadata is invalid");
    }
    final int rows =
        safeUpdate(
            """
            UPDATE ingestion_stage
               SET deidentified_ir = CAST(:payload AS jsonb),
                   phi_type_counts = CAST(:phiTypeCounts AS jsonb),
                   policy_version = :policyVersion,
                   processing_status = :processingStatus,
                   status = 'DEIDENTIFIED', updated_at = now()
             WHERE ingestion_run_id = :ingestionRunId
               AND document_version_id = :documentVersionId
               AND status = :expectedState
            """,
            identityParameters(ingestionRunId, documentVersionId)
                .addValue("expectedState", expectedState.name())
                .addValue("nextState", IngestionStageStatus.DEIDENTIFIED.name())
                .addValue("payload", writeSafeJson(deidentifiedIr, "de-identified IR"))
                .addValue("phiTypeCounts", writeSafeJson(phiTypeCounts, "PHI type counts"))
                .addValue("policyVersion", policyVersion)
                .addValue("processingStatus", processingStatus.name()),
            "de-identification stage write failed");
    requireOneRow(rows, "stage transition state mismatch");
  }

  @Override
  public void saveIndexingResult(
      final UUID ingestionRunId,
      final UUID documentVersionId,
      final IngestionStageStatus expectedState,
      final IndexingResult indexingResult) {
    requireExpected(expectedState, IngestionStageStatus.DEIDENTIFIED, "index preparation");
    if (indexingResult == null) {
      throw new StagePersistenceException("indexing result is required");
    }
    transitionWithJson(
        ingestionRunId,
        documentVersionId,
        expectedState,
        IngestionStageStatus.INDEX_READY,
        "indexing_result",
        writeSafeJson(indexingResult, "indexing result"));
  }

  @Override
  public void markIndexed(
      final UUID ingestionRunId,
      final UUID documentVersionId,
      final IngestionStageStatus expectedState) {
    requireExpected(expectedState, IngestionStageStatus.INDEX_READY, "index completion");
    final int rows =
        safeUpdate(
            """
            UPDATE ingestion_stage
               SET status = 'INDEXED', updated_at = now()
             WHERE ingestion_run_id = :ingestionRunId
               AND document_version_id = :documentVersionId
               AND status = :expectedState
            """,
            identityParameters(ingestionRunId, documentVersionId)
                .addValue("expectedState", expectedState.name()),
            "index completion write failed");
    requireOneRow(rows, "index completion state mismatch");
  }

  @Override
  public void quarantine(
      final UUID ingestionRunId,
      final UUID documentVersionId,
      final IngestionStageStatus expectedState,
      final QuarantineStage stage,
      final String errorCode,
      final String safeReason) {
    if (expectedState == null
        || expectedState == IngestionStageStatus.INDEXED
        || expectedState == IngestionStageStatus.QUARANTINED) {
      throw new StagePersistenceException("invalid expected state for quarantine");
    }
    if (stage == null) {
      throw new StagePersistenceException("quarantine stage is required");
    }
    if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
      throw new StagePersistenceException("invalid quarantine error code");
    }
    final String checkedReason = requireSafeReason(safeReason);
    final int rows =
        safeUpdate(
            """
            WITH quarantined AS (
              UPDATE ingestion_stage
                 SET status = 'QUARANTINED', quarantine_stage = :quarantineStage,
                     error_code = :errorCode, safe_reason = :safeReason, updated_at = now()
               WHERE ingestion_run_id = :ingestionRunId
                 AND document_version_id = :documentVersionId
                 AND status = :expectedState
              RETURNING source_uri, content_hash
            )
            INSERT INTO quarantine(source_uri, failure_stage, failure_reason, content_hash)
            SELECT source_uri, :quarantineStage, CONCAT(:errorCode, ': ', :safeReason), content_hash
              FROM quarantined
            ON CONFLICT (source_uri, content_hash, failure_stage, failure_reason)
            DO UPDATE SET failure_reason = quarantine.failure_reason
            """,
            identityParameters(ingestionRunId, documentVersionId)
                .addValue("expectedState", expectedState.name())
                .addValue("quarantineStage", stage.name())
                .addValue("errorCode", errorCode)
                .addValue("safeReason", checkedReason),
            "quarantine stage and queue write failed");
    requireOneRow(rows, "quarantine state mismatch");
  }

  private void transitionWithJson(
      final UUID ingestionRunId,
      final UUID documentVersionId,
      final IngestionStageStatus expectedState,
      final IngestionStageStatus nextState,
      final String column,
      final String json) {
    final int rows =
        safeUpdate(
            """
            UPDATE ingestion_stage
               SET %s = CAST(:payload AS jsonb), status = :nextState, updated_at = now()
             WHERE ingestion_run_id = :ingestionRunId
               AND document_version_id = :documentVersionId
               AND status = :expectedState
            """
                .formatted(column),
            identityParameters(ingestionRunId, documentVersionId)
                .addValue("expectedState", expectedState.name())
                .addValue("nextState", nextState.name())
                .addValue("payload", json),
            "stage transition write failed");
    requireOneRow(rows, "stage transition state mismatch");
  }

  private RowMapper<DurableStageItem> rowMapper() {
    return (resultSet, rowNumber) -> {
      try {
        final String deidentifiedJson = resultSet.getString("deidentified_ir");
        final String indexingJson = resultSet.getString("indexing_result");
        final String phiTypeCountsJson = resultSet.getString("phi_type_counts");
        final String quarantine = resultSet.getString("quarantine_stage");
        return new DurableStageItem(
            resultSet.getObject("ingestion_run_id", UUID.class),
            resultSet.getObject("logical_document_id", UUID.class),
            resultSet.getObject("document_version_id", UUID.class),
            new URI(resultSet.getString("source_uri")),
            resultSet.getString("source_id"),
            resultSet.getString("mime_type"),
            resultSet.getLong("size_bytes"),
            resultSet.getString("content_hash"),
            resultSet.getString("previous_content_hash"),
            DiscoveryClassification.valueOf(resultSet.getString("classification")),
            readMetadata(resultSet.getString("object_metadata")),
            resultSet.getBoolean("force_reprocess"),
            IngestionStageStatus.valueOf(resultSet.getString("status")),
            readNullable(deidentifiedJson, DocumentIR.class, "de-identified IR"),
            readIntegerMap(phiTypeCountsJson),
            resultSet.getString("policy_version"),
            nullableProcessingStatus(resultSet.getString("processing_status")),
            readNullable(indexingJson, IndexingResult.class, "indexing result"),
            quarantine == null ? null : QuarantineStage.valueOf(quarantine),
            resultSet.getString("error_code"),
            resultSet.getString("safe_reason"));
      } catch (final StagePersistenceException exception) {
        throw exception;
      } catch (final URISyntaxException | IllegalArgumentException exception) {
        throw new StagePersistenceException("persisted stage metadata is invalid");
      }
    };
  }

  Map<String, String> readMetadata(final String json) {
    try {
      final JsonNode tree = objectMapper.readTree(json);
      assertSafeJson(tree);
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JsonProcessingException | IllegalArgumentException exception) {
      throw new StagePersistenceException("persisted object metadata is invalid");
    }
  }

  private Map<String, Integer> readIntegerMap(final String json) {
    if (json == null) {
      return Map.of();
    }
    try {
      final JsonNode tree = objectMapper.readTree(json);
      assertSafeJson(tree);
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (final JsonProcessingException | IllegalArgumentException exception) {
      throw new StagePersistenceException("persisted PHI type counts are invalid");
    }
  }

  private static ProcessingStatus nullableProcessingStatus(final String value) {
    return value == null ? null : ProcessingStatus.valueOf(value);
  }

  <T> T readNullable(final String json, final Class<T> type, final String payloadName) {
    if (json == null) {
      return null;
    }
    try {
      final JsonNode tree = objectMapper.readTree(json);
      assertSafeJson(tree);
      return objectMapper.readValue(json, type);
    } catch (final JsonProcessingException | IllegalArgumentException exception) {
      throw new StagePersistenceException("persisted " + payloadName + " is invalid");
    }
  }

  private String writeSafeJson(final Object value, final String payloadName) {
    try {
      final JsonNode tree = objectMapper.valueToTree(value);
      assertSafeJson(tree);
      return objectMapper.writeValueAsString(tree);
    } catch (final IllegalArgumentException | JsonProcessingException exception) {
      throw new StagePersistenceException(payloadName + " serialization failed");
    }
  }

  private static void assertSafeJson(final JsonNode node) {
    if (node.isObject()) {
      final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        final Map.Entry<String, JsonNode> field = fields.next();
        if (FORBIDDEN_JSON_FIELDS.contains(normalize(field.getKey()))) {
          throw new IllegalArgumentException("forbidden persisted field");
        }
        assertSafeJson(field.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(JdbcDurableStageRepository::assertSafeJson);
    }
  }

  private static String normalize(final String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private static MapSqlParameterSource identityParameters(
      final UUID ingestionRunId, final UUID documentVersionId) {
    requireIdentity(ingestionRunId, "ingestionRunId");
    requireIdentity(documentVersionId, "documentVersionId");
    return new MapSqlParameterSource()
        .addValue("ingestionRunId", ingestionRunId)
        .addValue("documentVersionId", documentVersionId);
  }

  private static void requireIdentity(final UUID value, final String name) {
    if (value == null) {
      throw new StagePersistenceException(name + " is required");
    }
  }

  private static void requireExpected(
      final IngestionStageStatus actual,
      final IngestionStageStatus required,
      final String transitionName) {
    if (actual != required) {
      throw new StagePersistenceException(transitionName + " expected state is invalid");
    }
  }

  private static String requireSafeReason(final String reason) {
    if (reason == null
        || reason.isBlank()
        || reason.length() > MAX_SAFE_REASON_LENGTH
        || reason.indexOf('\n') >= 0
        || reason.indexOf('\r') >= 0) {
      throw new StagePersistenceException("invalid safe quarantine reason");
    }
    return reason;
  }

  private static void requireOneRow(final int rows, final String message) {
    if (rows != 1) {
      throw new StagePersistenceException(message);
    }
  }

  private int safeUpdate(
      final String sql, final MapSqlParameterSource parameters, final String failureMessage) {
    try {
      return jdbc.update(sql, parameters);
    } catch (final RuntimeException exception) {
      throw new StagePersistenceException(failureMessage);
    }
  }
}
