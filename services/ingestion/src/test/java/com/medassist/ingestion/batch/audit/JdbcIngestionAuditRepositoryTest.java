package com.medassist.ingestion.batch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditFailure;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditItem;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunParameters;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunStatus;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditStage;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditStep;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.StepAggregate;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcIngestionAuditRepositoryTest {
  private static final UUID RUN_ID = UUID.randomUUID();

  @Test
  void startsAndFinishesRunWithParameterizedSafeValues() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();
    final JdbcIngestionAuditRepository repository = repository(jdbc);

    final UUID runId =
        repository.startRun(
            RUN_ID,
            new AuditRunParameters(Instant.parse("2026-08-07T10:15:30Z"), "guidelines", true));
    assertThat(runId).isEqualTo(RUN_ID);
    repository.finishRun(runId, AuditRunStatus.COMPLETED);

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, times(2)).update(sql.capture(), parameters.capture());
    assertThat(sql.getAllValues().get(0))
        .contains(":runId", ":parameters")
        .doesNotContain("guidelines");
    assertThat(parameters.getAllValues().get(0).getValue("parameters").toString())
        .isEqualTo(
            "{\"requestedAt\":\"2026-08-07T10:15:30Z\",\"sourceScope\":\"guidelines\",\"forceReprocess\":true}");
    assertThat(sql.getAllValues().get(1)).contains(":status", ":runId");
    assertThat(parameters.getAllValues().get(1).getValue("status")).isEqualTo("COMPLETED");
  }

  @Test
  void mapsAllFourStepsThroughFixedSqlAllowlist() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();
    final JdbcIngestionAuditRepository repository = repository(jdbc);

    for (final AuditStep step : AuditStep.values()) {
      repository.updateStepAggregate(RUN_ID, new StepAggregate(step, 7, 5, 2, 90));
    }

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(8)).update(sql.capture(), any(SqlParameterSource.class));
    final List<String> statements = sql.getAllValues();
    assertThat(statements.get(0)).contains("discovered_count = discovered_count + :writeCount");
    assertThat(statements.get(2))
        .contains("parsed_count = parsed_count + :readCount")
        .contains("deidentified_count = deidentified_count + :writeCount");
    assertThat(statements.get(4))
        .contains("chunked_count = chunked_count + :writeCount")
        .contains("embedded_count = embedded_count + :writeCount");
    assertThat(statements.get(6)).contains("indexed_count = indexed_count + :writeCount");
    assertThat(statements.get(0)).contains("skipped_count = skipped_count + :skipCount");
    assertThat(statements).allMatch(statement -> !statement.contains("patient"));
  }

  @Test
  void unknownStepFailsClosedBeforeJdbc() {
    assertThatThrownBy(() -> AuditStep.fromBatchStepName("external-step; DROP TABLE ingestion_run"))
        .isInstanceOf(AuditPersistenceException.class)
        .hasMessage("unknown ingestion step");
  }

  @Test
  void itemFailureAcceptsOnlyFixedErrorAndDoesNotInlineSourceUri() {
    final NamedParameterJdbcTemplate jdbc = successfulJdbc();
    final JdbcIngestionAuditRepository repository = repository(jdbc);
    final URI sourceUri = URI.create("s3://private/patient-secret.pdf");

    repository.recordItem(
        RUN_ID,
        AuditItem.failed(
            UUID.randomUUID(),
            UUID.randomUUID(),
            sourceUri,
            AuditStage.PARSE_AND_DEIDENTIFY,
            42,
            AuditFailure.PARSE_FAILED));

    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<SqlParameterSource> parameters =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(sql.capture(), parameters.capture());
    assertThat(sql.getValue()).contains(":sourceUri", ":errorCode", ":safeReason");
    assertThat(sql.getValue()).doesNotContain(sourceUri.toString(), "patient-secret");
    assertThat(parameters.getValue().getValue("errorCode")).isEqualTo("PARSE_FAILED");
    assertThat(parameters.getValue().getValue("safeReason")).isEqualTo("Parsing stage failed");
  }

  @Test
  void jdbcFailureDoesNotLeakThrowableMessageOrCause() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class)))
        .thenThrow(new IllegalStateException("s3://private/patient-secret stack trace"));

    assertThatThrownBy(() -> repository(jdbc).finishRun(RUN_ID, AuditRunStatus.FAILED))
        .isInstanceOf(AuditPersistenceException.class)
        .hasMessage("audit run finish failed")
        .hasNoCause()
        .message()
        .doesNotContain("patient-secret", "stack trace");
  }

  private static NamedParameterJdbcTemplate successfulJdbc() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    return jdbc;
  }

  private static JdbcIngestionAuditRepository repository(final NamedParameterJdbcTemplate jdbc) {
    return new JdbcIngestionAuditRepository(jdbc, new ObjectMapper());
  }
}
