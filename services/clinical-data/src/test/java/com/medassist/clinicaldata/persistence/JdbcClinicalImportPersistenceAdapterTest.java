package com.medassist.clinicaldata.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import com.medassist.clinicaldata.config.ClinicalImportProperties;
import com.medassist.clinicaldata.deid.SafeHarborMapper;
import com.medassist.clinicaldata.fhir.FhirBundleImportDto;
import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.fhir.FhirPayloadFormat;
import com.medassist.clinicaldata.fhir.FhirProfileValidator;
import com.medassist.clinicaldata.fhir.HapiFhirBundleImporter;
import com.medassist.clinicaldata.model.CodingValue;
import com.medassist.clinicaldata.model.ConditionRecord;
import com.medassist.clinicaldata.model.EncounterRecord;
import com.medassist.clinicaldata.model.MedicationRecord;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import com.medassist.clinicaldata.quarantine.QuarantineReason;
import com.medassist.clinicaldata.quarantine.QuarantineRecord;
import com.medassist.clinicaldata.quarantine.QuarantineStage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("unchecked")
class JdbcClinicalImportPersistenceAdapterTest {
  private static final CodingValue CODE = new CodingValue("http://loinc.org", "1234-5", "code");
  private static final UUID RUN_ID = UUID.randomUUID();

  @Test
  void writesEverySafeHarborRelationAndQuarantineWithParameterizedIdempotentSql() {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    final JdbcClinicalImportPersistenceAdapter adapter = adapter(jdbc);

    final ClinicalImportPersistenceResult result = adapter.persist("source-a", importResult());

    assertThat(result.acceptedInsertedCount()).isEqualTo(5);
    assertThat(result.quarantinedInsertedCount()).isEqualTo(1);
    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, org.mockito.Mockito.times(8)).update(sql.capture(), parameters.capture());
    final String combinedSql = String.join("\n", sql.getAllValues());
    assertThat(combinedSql)
        .contains(
            "clinical_patient",
            "clinical_encounter",
            "clinical_condition",
            "clinical_medication",
            "clinical_observation",
            "clinical_quarantine",
            "clinical_import_run")
        .contains("ON CONFLICT")
        .doesNotContain("payload", "raw_fhir", "birth_date", "zip_code");
    final int quarantineIndex =
        java.util.stream.IntStream.range(0, sql.getAllValues().size())
            .filter(index -> sql.getAllValues().get(index).contains("clinical_quarantine"))
            .findFirst()
            .orElseThrow();
    assertThat(parameters.getAllValues().get(quarantineIndex))
        .doesNotContain("not-json", "Patient Name");
  }

  @Test
  void treatsConflictRowsAsIdempotentAndKeepsTheImportRunSuccessful() {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              final String sql = invocation.getArgument(0, String.class);
              return sql.contains("clinical_patient") ? 0 : 1;
            });

    final ClinicalImportPersistenceResult result =
        adapter(jdbc)
            .persist(
                "source-a",
                new FhirBundleImportResult(
                    List.of(new PatientRecord("p-1", 1980, "40-49", "female", null, null, "123")),
                    List.of()));

    assertThat(result.acceptedInsertedCount()).isZero();
    assertThat(result.quarantinedInsertedCount()).isZero();
  }

  @Test
  void rejectsUnsafeQuarantineMetadataBeforeOpeningTheTransaction() {
    final JdbcOperations jdbc = mock(JdbcOperations.class);
    final QuarantineRecord unsafe =
        new QuarantineRecord(
            "source-a",
            "Patient",
            "p-1",
            QuarantineStage.MAPPING,
            QuarantineReason.MAPPING_FAILED,
            "line one\nline two");

    assertThatThrownBy(
            () ->
                adapter(jdbc)
                    .persist("source-a", new FhirBundleImportResult(List.of(), List.of(unsafe))))
        .isInstanceOf(ClinicalDataPersistenceException.class);
    verifyNoInteractions(jdbc);
  }

  @Test
  void passesHapiQuarantineResultIntoThePersistenceService() {
    final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    final HapiFhirBundleImporter importer =
        new HapiFhirBundleImporter(
            FhirContext.forR4(),
            new FhirProfileValidator(ClinicalImportProperties.defaults()),
            new SafeHarborMapper(clock));
    final String[] capturedSource = new String[1];
    final FhirBundleImportResult[] capturedResult = new FhirBundleImportResult[1];
    final ClinicalImportPersistencePort persistence =
        (sourceId, importResult) -> {
          capturedSource[0] = sourceId;
          capturedResult[0] = importResult;
          return new ClinicalImportPersistenceResult(RUN_ID, 0, 1);
        };

    final ClinicalImportPersistenceResult result =
        new ClinicalBundleImportService(importer, persistence)
            .importBundle(new FhirBundleImportDto("source-a", "not-json", FhirPayloadFormat.JSON));

    assertThat(result.importRunId()).isEqualTo(RUN_ID);
    assertThat(capturedSource[0]).isEqualTo("source-a");
    assertThat(capturedResult[0].records()).isEmpty();
    assertThat(capturedResult[0].quarantines()).hasSize(1);
  }

  private static JdbcClinicalImportPersistenceAdapter adapter(final JdbcOperations jdbc) {
    final TransactionOperations transactions =
        new TransactionOperations() {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    return new JdbcClinicalImportPersistenceAdapter(jdbc, transactions);
  }

  private static FhirBundleImportResult importResult() {
    return new FhirBundleImportResult(
        List.of(
            new PatientRecord("p-1", 1980, "40-49", "female", "white", null, "123"),
            new EncounterRecord("e-1", "p-1", CODE, 2020, 2021, CODE),
            new ConditionRecord("c-1", "p-1", "e-1", CODE, "condition", 2020, "active"),
            new MedicationRecord("m-1", "p-1", "e-1", CODE, "medication", 2020, null, "active"),
            new ObservationRecord("o-1", "p-1", "e-1", CODE, "observation", "120", "mmHg", 2020)),
        List.of(
            new QuarantineRecord(
                "source-a",
                "Organization",
                "org-1",
                QuarantineStage.PROFILE_VALIDATION,
                QuarantineReason.RESOURCE_TYPE_UNSUPPORTED,
                "resource type is not supported")));
  }
}
