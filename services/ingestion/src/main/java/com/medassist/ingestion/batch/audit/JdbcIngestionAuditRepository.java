package com.medassist.ingestion.batch.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditFailure;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditItem;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunParameters;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunStatus;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.StepAggregate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized PostgreSQL adapter for ingestion audit metadata. */
@Repository
public class JdbcIngestionAuditRepository implements IngestionAuditRepository {
  private static final String STEP_SUMMARY_URI_PREFIX = "audit://step/";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcIngestionAuditRepository(
      final NamedParameterJdbcTemplate jdbc, final ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public UUID startRun(final UUID runId, final AuditRunParameters parameters) {
    requireRunId(runId);
    if (parameters == null) {
      throw new AuditPersistenceException("audit run parameters are required");
    }
    final MapSqlParameterSource sqlParameters =
        new MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("parameters", serializeParameters(parameters));
    executeOne(
        """
        INSERT INTO ingestion_run(id, status, parameters)
        VALUES (:runId, 'STARTED', CAST(:parameters AS jsonb))
        ON CONFLICT (id) DO UPDATE
          SET status = 'STARTED', finished_at = NULL
          WHERE ingestion_run.parameters = EXCLUDED.parameters
        """,
        sqlParameters,
        "audit run start failed");
    return runId;
  }

  @Override
  public void finishRun(final UUID runId, final AuditRunStatus status) {
    requireRunId(runId);
    if (status == null) {
      throw new AuditPersistenceException("audit run status is required");
    }
    executeOne(
        """
        UPDATE ingestion_run
           SET status = :status, finished_at = now()
         WHERE id = :runId
        """,
        new MapSqlParameterSource("runId", runId).addValue("status", status.name()),
        "audit run finish failed");
  }

  @Override
  public void updateStepAggregate(final UUID runId, final StepAggregate aggregate) {
    requireRunId(runId);
    if (aggregate == null) {
      throw new AuditPersistenceException("step aggregate is required");
    }
    final String sql = aggregateSql(aggregate);
    final MapSqlParameterSource parameters =
        new MapSqlParameterSource("runId", runId)
            .addValue("readCount", aggregate.readCount())
            .addValue("writeCount", aggregate.writeCount())
            .addValue("skipCount", aggregate.skipCount());
    executeOne(sql, parameters, "audit step update failed");
    recordStepSummary(runId, aggregate);
  }

  @Override
  public void recordItem(final UUID runId, final AuditItem item) {
    requireRunId(runId);
    if (item == null) {
      throw new AuditPersistenceException("audit item is required");
    }
    final AuditFailure failure = item.failure();
    executeOne(
        """
        INSERT INTO ingestion_item(
          id, ingestion_run_id, document_id, document_version_id, source_uri, stage, status,
          duration_ms, error_code, error_message)
        VALUES (
          :itemId, :runId, :documentId, :documentVersionId, :sourceUri, :stage, :status,
          :durationMs, :errorCode, :safeReason)
        """,
        new MapSqlParameterSource()
            .addValue("itemId", UUID.randomUUID())
            .addValue("runId", runId)
            .addValue("documentId", item.documentId())
            .addValue("documentVersionId", item.documentVersionId())
            .addValue("sourceUri", item.sourceUri().toString())
            .addValue("stage", item.stage().name())
            .addValue("status", item.status().name())
            .addValue("durationMs", item.durationMs())
            .addValue("errorCode", failure == null ? null : failure.errorCode())
            .addValue("safeReason", failure == null ? null : failure.safeReason()),
        "audit item write failed");
  }

  private void recordStepSummary(final UUID runId, final StepAggregate aggregate) {
    executeOne(
        """
        INSERT INTO ingestion_item(
          id, ingestion_run_id, source_uri, stage, status, duration_ms)
        VALUES (:itemId, :runId, :sourceUri, :stage, 'SUCCEEDED', :durationMs)
        """,
        new MapSqlParameterSource()
            .addValue("itemId", UUID.randomUUID())
            .addValue("runId", runId)
            .addValue("sourceUri", STEP_SUMMARY_URI_PREFIX + aggregate.step().batchStepName())
            .addValue("stage", aggregate.step().stage().name())
            .addValue("durationMs", aggregate.durationMs()),
        "audit step duration write failed");
  }

  private static String aggregateSql(final StepAggregate aggregate) {
    final String increments =
        switch (aggregate.step()) {
          case DISCOVER_DOCUMENTS -> "discovered_count = discovered_count + :writeCount";
          case PARSE_AND_DEIDENTIFY ->
              "parsed_count = parsed_count + :readCount, "
                  + "deidentified_count = deidentified_count + :writeCount";
          case CHUNK_AND_EMBED ->
              "chunked_count = chunked_count + :writeCount, "
                  + "embedded_count = embedded_count + :writeCount";
          case INDEX -> "indexed_count = indexed_count + :writeCount";
        };
    return "UPDATE ingestion_run SET "
        + increments
        + ", skipped_count = skipped_count + :skipCount WHERE id = :runId";
  }

  private String serializeParameters(final AuditRunParameters parameters) {
    final Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("requestedAt", parameters.requestedAt().toString());
    safe.put("sourceScope", parameters.sourceScope());
    safe.put("forceReprocess", parameters.forceReprocess());
    try {
      return objectMapper.writeValueAsString(safe);
    } catch (final JsonProcessingException exception) {
      throw new AuditPersistenceException("audit parameter serialization failed");
    }
  }

  private void executeOne(
      final String sql, final MapSqlParameterSource parameters, final String failureMessage) {
    try {
      if (jdbc.update(sql, parameters) != 1) {
        throw new AuditPersistenceException(failureMessage);
      }
    } catch (final AuditPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new AuditPersistenceException(failureMessage);
    }
  }

  private static void requireRunId(final UUID runId) {
    if (runId == null) {
      throw new AuditPersistenceException("audit run id is required");
    }
  }
}
