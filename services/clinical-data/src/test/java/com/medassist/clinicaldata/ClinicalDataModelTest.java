package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.clinicaldata.config.ClinicalImportProperties;
import com.medassist.clinicaldata.config.ClinicalQueryProperties;
import com.medassist.clinicaldata.fhir.FhirBundleImportDto;
import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.fhir.FhirPayloadFormat;
import com.medassist.clinicaldata.fhir.FhirValidationIssue;
import com.medassist.clinicaldata.model.CodingValue;
import com.medassist.clinicaldata.model.ConditionRecord;
import com.medassist.clinicaldata.model.EncounterRecord;
import com.medassist.clinicaldata.model.MedicationRecord;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import com.medassist.clinicaldata.quarantine.QuarantineReason;
import com.medassist.clinicaldata.quarantine.QuarantineRecord;
import com.medassist.clinicaldata.quarantine.QuarantineStage;
import com.medassist.clinicaldata.query.StructuredAggregateRow;
import com.medassist.clinicaldata.query.StructuredQueryRequest;
import com.medassist.clinicaldata.query.StructuredQueryResult;
import com.medassist.clinicaldata.query.StructuredResultColumn;
import com.medassist.clinicaldata.query.StructuredView;
import com.medassist.clinicaldata.research.ResearchAggregateRow;
import com.medassist.clinicaldata.research.ResearchQueryAuditEvent;
import com.medassist.clinicaldata.research.ResearchQueryResult;
import com.medassist.clinicaldata.research.ResearchView;
import com.medassist.clinicaldata.research.ResearchViewQuery;
import com.medassist.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClinicalDataModelTest {
  private static final CodingValue CODE = new CodingValue("system", "code", "display");

  @Test
  void validatesConfigurationAndCopiesAllowedViews() {
    assertThat(ClinicalImportProperties.defaults().requireProfile()).isTrue();
    assertThatThrownBy(() -> new ClinicalImportProperties(true, " "))
        .isInstanceOf(IllegalArgumentException.class);

    final ClinicalQueryProperties properties = ClinicalQueryProperties.defaults();
    assertThat(properties.allowedViews()).contains("clinical_research_condition_counts");
    assertThatThrownBy(() -> new ClinicalQueryProperties(1, 1, 1, true, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClinicalQueryProperties(2, 0, 1, true, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClinicalQueryProperties(2, 1, 0, true, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClinicalQueryProperties(2, 1, 1, false, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);

    final Set<String> mutable = new java.util.HashSet<>(Set.of("view"));
    final ClinicalQueryProperties copied = new ClinicalQueryProperties(2, 1, 1, true, mutable);
    mutable.add("other");
    assertThat(copied.allowedViews()).containsExactly("view");
  }

  @Test
  void validatesImportAndValueObjectContracts() {
    assertThatThrownBy(() -> new FhirBundleImportDto("", "payload", FhirPayloadFormat.JSON))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FhirBundleImportDto("source", "", FhirPayloadFormat.JSON))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FhirBundleImportDto("source", "payload", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FhirValidationIssue("", "message"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FhirValidationIssue("CODE", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodingValue("system", "", "display"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CODE.requireSystem("other"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CODE.requireSystem(null)).isInstanceOf(NullPointerException.class);

    final QuarantineRecord quarantine =
        new QuarantineRecord(
            "source",
            "Patient",
            "p-1",
            QuarantineStage.MAPPING,
            QuarantineReason.MAPPING_FAILED,
            "mapping failed");
    assertThat(quarantine.reason()).doesNotContain("payload");
    assertThatThrownBy(
            () ->
                new QuarantineRecord(
                    "source", "Patient", "p-1", QuarantineStage.MAPPING, null, "reason"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new QuarantineRecord(
                    "source",
                    "Patient",
                    "p-1",
                    QuarantineStage.MAPPING,
                    QuarantineReason.PARSE_FAILED,
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesClinicalRecordsAndExposesStableResourceIdentity() {
    final PatientRecord patient = new PatientRecord("p-1", 1980, "40", "male", null, null, null);
    assertThat(patient.resourceType()).isEqualTo("Patient");
    assertThat(patient.resourceId()).isEqualTo("p-1");
    assertThatThrownBy(() -> new PatientRecord("p", 1899, "40", "male", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PatientRecord("p", 1980, "40", "male", null, null, "12"))
        .isInstanceOf(IllegalArgumentException.class);

    final ConditionRecord condition =
        new ConditionRecord("c-1", "p-1", null, CODE, "display", null, "active");
    assertThat(condition.resourceType()).isEqualTo("Condition");
    assertThatThrownBy(() -> new ConditionRecord("c", "p", null, CODE, null, 1899, "active"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConditionRecord("c", "p", null, CODE, null, null, ""))
        .isInstanceOf(IllegalArgumentException.class);

    final EncounterRecord encounter = new EncounterRecord("e-1", "p-1", CODE, 2020, 2021, null);
    assertThat(encounter.resourceType()).isEqualTo("Encounter");
    assertThatThrownBy(() -> new EncounterRecord("e", "p", CODE, 1899, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EncounterRecord("e", "p", CODE, 2020, 2019, null))
        .isInstanceOf(IllegalArgumentException.class);

    final MedicationRecord medication =
        new MedicationRecord("m-1", "p-1", null, CODE, "display", 2020, null, "active");
    assertThat(medication.resourceType()).isEqualTo("Medication");
    assertThatThrownBy(
            () -> new MedicationRecord("m", "p", null, CODE, "display", 2020, 2019, "active"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MedicationRecord("m", "p", null, CODE, "display", 1899, null, "active"))
        .isInstanceOf(IllegalArgumentException.class);

    final ObservationRecord observation =
        new ObservationRecord("o-1", "p-1", null, CODE, "display", "value", null, 2020);
    assertThat(observation.resourceType()).isEqualTo("Observation");
    assertThatThrownBy(
            () -> new ObservationRecord("o", "p", null, CODE, "display", "value", null, 2200))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObservationRecord("o", "p", null, CODE, "display", "", null, 2020))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiesResultsAndRejectsUnsafeAggregateShapes() {
    final FhirBundleImportResult importResult = new FhirBundleImportResult(List.of(), List.of());
    assertThat(importResult.acceptedCount()).isZero();
    assertThat(importResult.quarantinedCount()).isZero();

    assertThatThrownBy(() -> new StructuredAggregateRow(Map.of("patient_id", "p-1"), 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StructuredAggregateRow(Map.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    final StructuredAggregateRow row = new StructuredAggregateRow(Map.of("code", "C01"), 5);
    final StructuredQueryResult queryResult =
        new StructuredQueryResult(
            StructuredView.CONDITION_COUNTS,
            List.of(new StructuredResultColumn("count", "aggregate count")),
            List.of(row),
            false,
            false);
    assertThat(queryResult.rows()).containsExactly(row);
    assertThatThrownBy(() -> new StructuredResultColumn("", "description"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StructuredQueryResult(null, List.of(), List.of(), false, false))
        .isInstanceOf(IllegalArgumentException.class);

    final StructuredQueryRequest request =
        new StructuredQueryRequest(
            "actor", Role.CLINICIAN, StructuredView.CONDITION_COUNTS, "select 1", false, null);
    assertThat(request.actor()).isEqualTo("actor");
    assertThatThrownBy(
            () ->
                new StructuredQueryRequest(
                    "actor", Role.CLINICIAN, StructuredView.CONDITION_COUNTS, "select 1", true, ""))
        .isInstanceOf(IllegalArgumentException.class);

    final ResearchAggregateRow researchRow = new ResearchAggregateRow(Map.of("code", "C01"), 5);
    assertThat(researchRow.patientCount()).isEqualTo(5);
    assertThatThrownBy(() -> new ResearchAggregateRow(Map.of("encounter_id", "e"), 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResearchAggregateRow(Map.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchQueryAuditEvent(
                    Instant.EPOCH,
                    "actor",
                    Role.RESEARCHER,
                    ResearchView.CONDITION_COUNTS,
                    "ALLOWED",
                    -1,
                    0,
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchQueryAuditEvent(
                    Instant.EPOCH,
                    "actor",
                    Role.CLINICIAN,
                    ResearchView.CONDITION_COUNTS,
                    "ALLOWED",
                    0,
                    0,
                    true,
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchQueryResult(
                    ResearchView.CONDITION_COUNTS, List.of(), 1, false, 0, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResearchQueryResult(
                    ResearchView.CONDITION_COUNTS, List.of(), 5, false, -1, false))
        .isInstanceOf(IllegalArgumentException.class);

    final ResearchViewQuery researcher =
        ResearchViewQuery.researcher(ResearchView.CONDITION_COUNTS);
    assertThat(researcher.filters()).isEmpty();
    assertThatThrownBy(() -> new ResearchViewQuery(ResearchView.CONDITION_COUNTS, null, true, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
