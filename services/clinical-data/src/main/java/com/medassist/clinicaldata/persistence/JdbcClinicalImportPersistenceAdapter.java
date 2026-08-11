package com.medassist.clinicaldata.persistence;

import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.model.ClinicalRecord;
import com.medassist.clinicaldata.model.CodingValue;
import com.medassist.clinicaldata.model.ConditionRecord;
import com.medassist.clinicaldata.model.EncounterRecord;
import com.medassist.clinicaldata.model.MedicationRecord;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import com.medassist.clinicaldata.quarantine.QuarantineRecord;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for Safe Harbor clinical records.
 *
 * <p>The adapter is deliberately explicit rather than an auto-configured repository. The
 * clinical-data application can therefore remain usable without a database while ingestion owns
 * Flyway and supplies the transaction infrastructure when this boundary is composed.
 */
public final class JdbcClinicalImportPersistenceAdapter implements ClinicalImportPersistencePort {
  private static final int MAX_SAFE_TEXT_LENGTH = 256;

  private static final String START_RUN_SQL =
      """
      INSERT INTO clinical_import_run(id, source_id, status)
      VALUES (?, ?, 'STARTED')
      """;
  private static final String FINISH_RUN_SQL =
      """
      UPDATE clinical_import_run
         SET status = 'COMPLETED', accepted_count = ?, quarantined_count = ?, finished_at = now()
       WHERE id = ? AND status = 'STARTED'
      """;
  private static final String INSERT_PATIENT_SQL =
      """
      INSERT INTO clinical_patient(
        source_id, resource_id, birth_year, age_band, gender, race, ethnicity, zip3)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;
  private static final String INSERT_ENCOUNTER_SQL =
      """
      INSERT INTO clinical_encounter(
        source_id, resource_id, patient_id, type_system, type_code, type_display,
        start_year, end_year, reason_system, reason_code, reason_display)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;
  private static final String INSERT_CONDITION_SQL =
      """
      INSERT INTO clinical_condition(
        source_id, resource_id, patient_id, encounter_id, code_system, code, code_display,
        display, onset_year, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;
  private static final String INSERT_MEDICATION_SQL =
      """
      INSERT INTO clinical_medication(
        source_id, resource_id, patient_id, encounter_id, code_system, code, code_display,
        display, start_year, end_year, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;
  private static final String INSERT_OBSERVATION_SQL =
      """
      INSERT INTO clinical_observation(
        source_id, resource_id, patient_id, encounter_id, code_system, code, code_display,
        display, value, unit, observation_year)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;
  private static final String INSERT_QUARANTINE_SQL =
      """
      INSERT INTO clinical_quarantine(
        source_id, resource_type, resource_id, stage, reason_code, safe_reason)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_id, resource_id) DO NOTHING
      """;

  private final JdbcOperations jdbc;
  private final TransactionOperations transactions;

  public JdbcClinicalImportPersistenceAdapter(
      final JdbcOperations jdbc, final PlatformTransactionManager transactionManager) {
    this(jdbc, new TransactionTemplate(transactionManager));
  }

  public JdbcClinicalImportPersistenceAdapter(
      final JdbcOperations jdbc, final TransactionOperations transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public ClinicalImportPersistenceResult persist(
      final String sourceId, final FhirBundleImportResult importResult) {
    requireSafeText(sourceId, "sourceId");
    Objects.requireNonNull(importResult, "importResult");
    importResult.quarantines().forEach(quarantine -> validateQuarantine(sourceId, quarantine));
    try {
      final ClinicalImportPersistenceResult result =
          transactions.execute(status -> persistInTransaction(sourceId, importResult));
      if (result == null) {
        throw new ClinicalDataPersistenceException(
            "clinical import transaction returned no result");
      }
      return result;
    } catch (final ClinicalDataPersistenceException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new ClinicalDataPersistenceException("clinical import transaction failed", exception);
    }
  }

  private ClinicalImportPersistenceResult persistInTransaction(
      final String sourceId, final FhirBundleImportResult importResult) {
    final UUID importRunId = UUID.randomUUID();
    requireOneRow(
        jdbc.update(START_RUN_SQL, importRunId, sourceId), "clinical import run start failed");

    int acceptedInsertedCount = 0;
    for (final ClinicalRecord record : importResult.records()) {
      acceptedInsertedCount += persistRecord(sourceId, record);
    }

    int quarantinedInsertedCount = 0;
    for (final QuarantineRecord quarantine : importResult.quarantines()) {
      quarantinedInsertedCount += persistQuarantine(quarantine);
    }

    requireOneRow(
        jdbc.update(FINISH_RUN_SQL, acceptedInsertedCount, quarantinedInsertedCount, importRunId),
        "clinical import run finish failed");
    return new ClinicalImportPersistenceResult(
        importRunId, acceptedInsertedCount, quarantinedInsertedCount);
  }

  private int persistRecord(final String sourceId, final ClinicalRecord record) {
    Objects.requireNonNull(record, "record");
    final int rows =
        switch (record) {
          case PatientRecord patient ->
              jdbc.update(
                  INSERT_PATIENT_SQL,
                  sourceId,
                  patient.resourceId(),
                  patient.birthYear(),
                  patient.ageBand(),
                  patient.gender(),
                  patient.race(),
                  patient.ethnicity(),
                  patient.zip3());
          case EncounterRecord encounter ->
              jdbc.update(
                  INSERT_ENCOUNTER_SQL,
                  sourceId,
                  encounter.resourceId(),
                  encounter.patientId(),
                  codingSystem(encounter.type()),
                  encounter.type().code(),
                  encounter.type().display(),
                  encounter.startYear(),
                  encounter.endYear(),
                  codingSystem(encounter.reasonCode()),
                  codingCode(encounter.reasonCode()),
                  codingDisplay(encounter.reasonCode()));
          case ConditionRecord condition ->
              jdbc.update(
                  INSERT_CONDITION_SQL,
                  sourceId,
                  condition.resourceId(),
                  condition.patientId(),
                  condition.encounterId(),
                  codingSystem(condition.code()),
                  condition.code().code(),
                  condition.code().display(),
                  condition.display(),
                  condition.onsetYear(),
                  condition.status());
          case MedicationRecord medication ->
              jdbc.update(
                  INSERT_MEDICATION_SQL,
                  sourceId,
                  medication.resourceId(),
                  medication.patientId(),
                  medication.encounterId(),
                  codingSystem(medication.code()),
                  medication.code().code(),
                  medication.code().display(),
                  medication.display(),
                  medication.startYear(),
                  medication.endYear(),
                  medication.status());
          case ObservationRecord observation ->
              jdbc.update(
                  INSERT_OBSERVATION_SQL,
                  sourceId,
                  observation.resourceId(),
                  observation.patientId(),
                  observation.encounterId(),
                  codingSystem(observation.code()),
                  observation.code().code(),
                  observation.code().display(),
                  observation.display(),
                  observation.value(),
                  observation.unit(),
                  observation.observationYear());
        };
    requireAtMostOneRow(rows, "clinical record identity conflict");
    return rows;
  }

  private int persistQuarantine(final QuarantineRecord quarantine) {
    final int rows =
        jdbc.update(
            INSERT_QUARANTINE_SQL,
            quarantine.sourceId(),
            quarantine.resourceType(),
            quarantine.resourceId(),
            quarantine.stage().name(),
            quarantine.reasonCode().name(),
            quarantine.reason());
    requireAtMostOneRow(rows, "clinical quarantine identity conflict");
    return rows;
  }

  private static void validateQuarantine(final String sourceId, final QuarantineRecord quarantine) {
    Objects.requireNonNull(quarantine, "quarantine");
    if (!sourceId.equals(quarantine.sourceId())) {
      throw new ClinicalDataPersistenceException("quarantine source does not match import source");
    }
    requireSafeText(quarantine.resourceType(), "quarantine.resourceType");
    requireSafeText(quarantine.resourceId(), "quarantine.resourceId");
    requireSafeText(quarantine.reason(), "quarantine.reason");
  }

  private static String codingSystem(final CodingValue coding) {
    return coding == null ? null : coding.system();
  }

  private static String codingCode(final CodingValue coding) {
    return coding == null ? null : coding.code();
  }

  private static String codingDisplay(final CodingValue coding) {
    return coding == null ? null : coding.display();
  }

  private static void requireSafeText(final String value, final String field) {
    if (value == null
        || value.isBlank()
        || value.length() > MAX_SAFE_TEXT_LENGTH
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new ClinicalDataPersistenceException("invalid " + field);
    }
  }

  private static void requireOneRow(final int rows, final String message) {
    if (rows != 1) {
      throw new ClinicalDataPersistenceException(message);
    }
  }

  private static void requireAtMostOneRow(final int rows, final String message) {
    if (rows < 0 || rows > 1) {
      throw new ClinicalDataPersistenceException(message);
    }
  }
}
