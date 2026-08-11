package com.medassist.ingestion.batch.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.pipeline.index.IndexableChunk;
import com.medassist.ingestion.pipeline.index.IndexableDocument;
import com.medassist.ingestion.pipeline.index.IndexableEmbedding;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcDurableStageRepositoryTest {
  private static final UUID RUN_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();

  @Test
  void discoveredUpsertIsIdempotentAndDoesNotResetAdvancedState() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

    repository(jdbc).upsertDiscovered(discovered(Map.of("etag", "safe-etag")));

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(sql.capture(), parameters.capture());
    assertTrue(sql.getValue().contains("ON CONFLICT (ingestion_run_id, document_version_id)"));
    assertTrue(sql.getValue().contains("updated_at = ingestion_stage.updated_at"));
    assertFalse(sql.getValue().contains("SET status = 'DISCOVERED'"));
    assertEquals("{\"etag\":\"safe-etag\"}", parameters.getValue().getValue("objectMetadata"));
  }

  @Test
  void transitionUsesExpectedStateAndRejectsLostUpdate() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

    final StagePersistenceException failure =
        assertThrows(
            StagePersistenceException.class,
            () ->
                repository(jdbc)
                    .saveDeidentified(
                        RUN_ID,
                        VERSION_ID,
                        IngestionStageStatus.DISCOVERED,
                        deidentifiedIr(),
                        Map.of("PERSON", 1),
                        "policy-v1",
                        com.medassist.ingestion.pipeline.model.ProcessingStatus.SUCCEEDED));

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(sql.capture(), parameters.capture());
    assertTrue(sql.getValue().contains("AND status = :expectedState"));
    assertEquals("DISCOVERED", parameters.getValue().getValue("expectedState"));
    assertEquals("DEIDENTIFIED", parameters.getValue().getValue("nextState"));
    assertEquals("stage transition state mismatch", failure.getMessage());
  }

  @Test
  void readsOneStateWithStableNonContentOrdering() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(SqlParameterSource.class),
            any(org.springframework.jdbc.core.RowMapper.class)))
        .thenReturn(List.of());

    final List<DurableStageItem> items =
        repository(jdbc).findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY);

    assertTrue(items.isEmpty());
    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc)
        .query(
            sql.capture(),
            parameters.capture(),
            any(org.springframework.jdbc.core.RowMapper.class));
    assertTrue(sql.getValue().contains("ORDER BY source_id, document_version_id"));
    assertEquals("INDEX_READY", parameters.getValue().getValue("status"));
  }

  @Test
  void serializedSafePayloadsContainNoRawOrPhiOriginalFields() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    final JdbcDurableStageRepository repository = repository(jdbc);

    repository.saveDeidentified(
        RUN_ID,
        VERSION_ID,
        IngestionStageStatus.DISCOVERED,
        deidentifiedIr(),
        Map.of("PERSON", 1),
        "policy-v1",
        com.medassist.ingestion.pipeline.model.ProcessingStatus.SUCCEEDED);
    repository.saveIndexingResult(
        RUN_ID, VERSION_ID, IngestionStageStatus.DEIDENTIFIED, indexingResult());

    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), parameters.capture());
    final String persistedJson =
        String.valueOf(parameters.getAllValues().get(0).getValue("payload"))
            + parameters.getAllValues().get(1).getValue("payload");
    assertFalse(persistedJson.contains("rawContent"));
    assertFalse(persistedJson.contains("originalParserIr"));
    assertFalse(persistedJson.contains("parsedDocument"));
    assertFalse(persistedJson.contains("phiOriginal"));
    assertFalse(persistedJson.contains("stackTrace"));
    assertTrue(persistedJson.contains("deidentified text"));
  }

  @Test
  void unsafeObjectMetadataFieldIsRejectedBeforeJdbc() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    final StagePersistenceException failure =
        assertThrows(
            StagePersistenceException.class,
            () -> repository(jdbc).upsertDiscovered(discovered(Map.of("raw_text", "forbidden"))));

    assertEquals("object metadata serialization failed", failure.getMessage());
    org.mockito.Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void malformedPersistedJsonFailsClosedWithoutEchoingPayload() {
    final JdbcDurableStageRepository repository =
        repository(mock(NamedParameterJdbcTemplate.class));
    final String malformed = "{\"source_uri\":\"s3://private/patient-name\"";

    final StagePersistenceException failure =
        assertThrows(StagePersistenceException.class, () -> repository.readMetadata(malformed));

    assertEquals("persisted object metadata is invalid", failure.getMessage());
    assertFalse(failure.getMessage().contains("patient-name"));
    assertEquals(null, failure.getCause());
  }

  @Test
  void jdbcFailureDoesNotEchoUriOrPayloadFromDriverMessage() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class)))
        .thenThrow(
            new IllegalStateException(
                "driver failed for s3://medical-source/patient-name with {raw payload}"));

    final StagePersistenceException failure =
        assertThrows(
            StagePersistenceException.class,
            () -> repository(jdbc).upsertDiscovered(discovered(Map.of("etag", "safe-etag"))));

    assertEquals("discovered stage write failed", failure.getMessage());
    assertFalse(failure.getMessage().contains("patient-name"));
    assertFalse(failure.getMessage().contains("raw payload"));
    assertEquals(null, failure.getCause());
  }

  @Test
  void quarantineStoresOnlyCategoricalAndBoundedSafeFailureData() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

    repository(jdbc)
        .quarantine(
            RUN_ID,
            VERSION_ID,
            IngestionStageStatus.DEIDENTIFIED,
            QuarantineStage.PHI_SCAN,
            "PHI_RESCAN_SUSPECT",
            "Residual identifier category detected");

    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), parameters.capture());
    assertTrue(sql.getValue().contains("INSERT INTO quarantine"));
    assertTrue(sql.getValue().contains("WITH quarantined AS"));
    assertEquals("PHI_SCAN", parameters.getValue().getValue("quarantineStage"));
    assertEquals("PHI_RESCAN_SUSPECT", parameters.getValue().getValue("errorCode"));
    assertEquals(
        "Residual identifier category detected", parameters.getValue().getValue("safeReason"));

    assertThrows(
        StagePersistenceException.class,
        () ->
            repository(jdbc)
                .quarantine(
                    RUN_ID,
                    VERSION_ID,
                    IngestionStageStatus.INDEX_READY,
                    QuarantineStage.INDEXING,
                    "INDEX_FAILURE",
                    "exception detail\nstack trace"));
  }

  private static JdbcDurableStageRepository repository(final NamedParameterJdbcTemplate jdbc) {
    return new JdbcDurableStageRepository(jdbc, new ObjectMapper());
  }

  private static DiscoveredStageItem discovered(final Map<String, String> safeMetadata) {
    return new DiscoveredStageItem(
        RUN_ID,
        DOCUMENT_ID,
        VERSION_ID,
        URI.create("s3://medical-source/object-a"),
        "object-a",
        "application/pdf",
        1234,
        "sha256-current",
        null,
        DiscoveryClassification.NEW,
        safeMetadata,
        false);
  }

  private static DocumentIR deidentifiedIr() {
    return new DocumentIR(
        List.of(
            new Section(
                "1",
                "Deidentified heading",
                1,
                "deidentified text",
                List.of(),
                new SourceRange(0, 17))),
        List.of(),
        Map.of("document_type", "guideline"));
  }

  private static IndexingResult indexingResult() {
    final UUID chunkId = UUID.randomUUID();
    return new IndexingResult(
        new IndexableDocument(
            DOCUMENT_ID,
            VERSION_ID,
            "object-a",
            "Deidentified title",
            "Publisher",
            "policy-v1",
            Map.of("document_type", "guideline")),
        List.of(
            new IndexableChunk(
                chunkId,
                VERSION_ID,
                0,
                "1",
                "deidentified text",
                2,
                new SourceRange(0, 17),
                "Deidentified heading",
                "structure-v1",
                PhiScanStatus.CLEAN,
                Set.of(),
                "safe context")),
        List.of(new IndexableEmbedding(chunkId, "model", "v1", 2, List.of(0.1F, 0.2F))));
  }
}
