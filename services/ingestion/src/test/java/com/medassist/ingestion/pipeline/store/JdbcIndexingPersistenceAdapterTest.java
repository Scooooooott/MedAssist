package com.medassist.ingestion.pipeline.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.IndexableChunk;
import com.medassist.ingestion.pipeline.index.IndexableDocument;
import com.medassist.ingestion.pipeline.index.IndexableEmbedding;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import com.medassist.ingestion.versioning.VersionChainStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("unchecked")
class JdbcIndexingPersistenceAdapterTest {
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();
  private static final UUID CHUNK_ID = UUID.randomUUID();

  @Test
  void persistsSourceTextSeparatelyFromContextAndRoutes768Dimension() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID, VERSION_ID);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(VERSION_ID));
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(CHUNK_ID), List.of(CHUNK_ID));

    final JdbcIndexingPersistenceAdapter adapter = adapter(jdbc);
    final IndexingPersistenceResult persisted = adapter.persist(request(768));

    assertEquals(new IndexingPersistenceResult(1, 1), persisted);
    final var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    final var paramsCaptor = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(2))
        .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));
    assertEquals(
        "source-faithful deidentified text", paramsCaptor.getAllValues().get(0).getValue("text"));
    assertEquals("Synthetic context", paramsCaptor.getAllValues().get(0).getValue("contextPrefix"));
    assertEquals(true, sqlCaptor.getAllValues().get(1).contains("chunk_embedding_768"));
    assertEquals(
        false,
        String.valueOf(paramsCaptor.getAllValues().get(0).getValue("text"))
            .contains("Synthetic context"));
  }

  @Test
  void rejectsUnsupportedDimensionBeforeOpeningTransaction() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final JdbcIndexingPersistenceAdapter adapter = adapter(jdbc);

    assertThrows(IndexingPersistenceException.class, () -> adapter.persist(request(2048)));
    verifyNoInteractions(jdbc);
  }

  @Test
  void conflictingExistingRowsAreNotOverwritten() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID, VERSION_ID);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(VERSION_ID));
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(CHUNK_ID), List.of(CHUNK_ID));

    final JdbcIndexingPersistenceAdapter adapter = adapter(jdbc);
    adapter.persist(request(1536));

    final var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.times(2))
        .query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
    final String combinedSql = String.join("\n", sqlCaptor.getAllValues());
    assertEquals(false, combinedSql.contains("SET text"));
    assertEquals(false, combinedSql.contains("SET embedding"));
    assertEquals(true, combinedSql.contains("chunk_embedding_1536"));
  }

  @Test
  void failureDuringVersionWriteIsPropagatedAndNoChunkIsAttempted() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID)
        .thenThrow(new IllegalStateException("database unavailable"));

    final JdbcIndexingPersistenceAdapter adapter = adapter(jdbc);

    assertThrows(IndexingPersistenceException.class, () -> adapter.persist(request(1024)));
    verify(jdbc, org.mockito.Mockito.never())
        .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
  }

  @Test
  void unknownVersionIsQueuedForMetadataReviewWithoutVersionChainReconciliation() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbcForOneChunk();

    adapter(jdbc).persist(unknownVersionRequest());

    final var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    final var parameters = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), parameters.capture());
    final String combinedSql = String.join("\n", sql.getAllValues());
    assertTrue(combinedSql.contains("INSERT INTO document_metadata_review"));
    assertEquals(false, combinedSql.contains("UPDATE document_version"));
    final SqlParameterSource reviewParameters = parameters.getAllValues().get(1);
    assertEquals("MISSING_EFFECTIVE_DATE", reviewParameters.getValue("reasonCode"));
  }

  @Test
  void confirmedVersionsAreReconciledIntoOneActiveChain() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final UUID olderVersionId = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID, VERSION_ID);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(VERSION_ID, olderVersionId));
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(CHUNK_ID), List.of(CHUNK_ID));

    adapter(jdbc).persist(request(1024));

    final var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    final var parameters = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(3)).update(sql.capture(), parameters.capture());
    final List<SqlParameterSource> chainParameters =
        java.util.stream.IntStream.range(0, sql.getAllValues().size())
            .filter(index -> sql.getAllValues().get(index).contains("UPDATE document_version"))
            .mapToObj(index -> parameters.getAllValues().get(index))
            .toList();
    assertEquals(2, chainParameters.size());
    assertEquals("ACTIVE", chainParameters.get(0).getValue("status"));
    assertNull(chainParameters.get(0).getValue("supersededBy"));
    assertEquals("SUPERSEDED", chainParameters.get(1).getValue("status"));
    assertEquals(VERSION_ID, chainParameters.get(1).getValue("supersededBy"));
  }

  @Test
  void suspectChunkIsQueuedForPhiReviewAndHasNoEmbedding() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID, VERSION_ID);
    when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
        .thenReturn(List.of(VERSION_ID));
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(CHUNK_ID));

    final IndexingPersistenceResult result = adapter(jdbc).persist(suspectChunkRequest());

    assertEquals(new IndexingPersistenceResult(1, 0), result);
    final var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    final var parameters = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, org.mockito.Mockito.times(3)).update(sql.capture(), parameters.capture());
    final int reviewIndex =
        java.util.stream.IntStream.range(0, sql.getAllValues().size())
            .filter(index -> sql.getAllValues().get(index).contains("INSERT INTO chunk_phi_review"))
            .findFirst()
            .orElseThrow();
    assertEquals("SUSPECT", parameters.getAllValues().get(reviewIndex).getValue("phiScanStatus"));
    verify(jdbc, org.mockito.Mockito.times(1))
        .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
  }

  private static JdbcIndexingPersistenceAdapter adapter(final NamedParameterJdbcTemplate jdbc) {
    final TransactionOperations transactions =
        new TransactionOperations() {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    return new JdbcIndexingPersistenceAdapter(jdbc, transactions, new ObjectMapper());
  }

  private static NamedParameterJdbcTemplate successfulJdbcForOneChunk() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
        .thenReturn(DOCUMENT_ID, VERSION_ID);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(CHUNK_ID), List.of(CHUNK_ID));
    return jdbc;
  }

  private static IndexingPersistenceRequest request(final int dimension) {
    final IndexingResult result =
        new IndexingResult(
            new IndexableDocument(
                DOCUMENT_ID,
                VERSION_ID,
                "source-a",
                "Synthetic",
                "Publisher",
                "policy-v1",
                Map.of("safe", "metadata")),
            List.of(
                new IndexableChunk(
                    CHUNK_ID,
                    VERSION_ID,
                    0,
                    "1 > Section",
                    "source-faithful deidentified text",
                    4,
                    new SourceRange(10, 43),
                    "Synthetic > Section",
                    "structure-v1",
                    PhiScanStatus.CLEAN,
                    java.util.Set.of(),
                    "Synthetic context")),
            List.of(
                new IndexableEmbedding(
                    CHUNK_ID,
                    "medical-embed",
                    "v1",
                    dimension,
                    java.util.stream.IntStream.range(0, dimension)
                        .mapToObj(index -> (float) index)
                        .toList())));
    return new IndexingPersistenceRequest(
        result,
        new DocumentIdentity(
            DOCUMENT_ID, "minio", "s3://bucket/doc", "GUIDELINE", "Publisher", "Synthetic"),
        new DocumentVersionMetadata(
            VERSION_ID,
            "v1",
            "fingerprint-1",
            LocalDate.parse("2026-01-15"),
            Instant.parse("2026-08-07T00:00:00Z"),
            VersionChainStatus.ACTIVE,
            null,
            "s3://bucket/doc",
            "PUBLIC",
            Map.of("source", "synthetic")),
        ContextualRetrievalMode.RULE_BASED,
        "context-v1",
        Map.of("PERSON", 1));
  }

  private static IndexingPersistenceRequest unknownVersionRequest() {
    final IndexingPersistenceRequest confirmed = request(1024);
    return new IndexingPersistenceRequest(
        confirmed.result(),
        confirmed.identity(),
        new DocumentVersionMetadata(
            VERSION_ID,
            "Unknown",
            "fingerprint-1",
            null,
            Instant.parse("2026-08-07T00:00:00Z"),
            VersionChainStatus.UNKNOWN,
            null,
            "s3://bucket/doc",
            "PUBLIC",
            Map.of(
                "source", "synthetic",
                "version_metadata_review_fields", "effective_date,publisher,version")),
        confirmed.contextualMode(),
        confirmed.contextPromptVersion(),
        confirmed.deidentificationPhiTypeCounts());
  }

  private static IndexingPersistenceRequest suspectChunkRequest() {
    final IndexingPersistenceRequest clean = request(1024);
    final IndexableChunk suspect =
        new IndexableChunk(
            CHUNK_ID,
            VERSION_ID,
            0,
            "1 > Section",
            "source-faithful deidentified text",
            4,
            new SourceRange(10, 43),
            "Synthetic > Section",
            "structure-v1",
            PhiScanStatus.SUSPECT,
            java.util.Set.of("PERSON"),
            "Synthetic context");
    final IndexingResult unsafeResult =
        new IndexingResult(clean.result().document(), List.of(suspect), List.of());
    return new IndexingPersistenceRequest(
        unsafeResult,
        clean.identity(),
        clean.version(),
        clean.contextualMode(),
        clean.contextPromptVersion(),
        clean.deidentificationPhiTypeCounts());
  }
}
