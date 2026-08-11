package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import com.medassist.clinicaldata.config.ClinicalImportProperties;
import com.medassist.clinicaldata.deid.SafeHarborMapper;
import com.medassist.clinicaldata.fhir.FhirBundleImportDto;
import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.fhir.FhirPayloadFormat;
import com.medassist.clinicaldata.fhir.FhirProfileValidator;
import com.medassist.clinicaldata.fhir.HapiFhirBundleImporter;
import com.medassist.clinicaldata.model.ConditionRecord;
import com.medassist.clinicaldata.model.EncounterRecord;
import com.medassist.clinicaldata.model.MedicationRecord;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import com.medassist.clinicaldata.quarantine.QuarantineReason;
import com.medassist.clinicaldata.quarantine.QuarantineStage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

class FhirImportAndMappingTest {
  private static final FhirContext FHIR = FhirContext.forR4();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final ClinicalImportProperties PROPERTIES = ClinicalImportProperties.defaults();

  @Test
  void validatesBundleProfilesSupportedTypesAndConfigurationModes() {
    final FhirProfileValidator validator = new FhirProfileValidator(PROPERTIES);
    final Bundle empty = new Bundle();
    assertThat(validator.validateBundle(empty))
        .extracting(issue -> issue.code())
        .containsExactlyInAnyOrder(
            "REQUIRED_FIELD_MISSING",
            "PROFILE_MISSING",
            "REQUIRED_FIELD_MISSING",
            "REQUIRED_FIELD_MISSING");

    final Patient mismatch = validPatient(validator, "p-1");
    mismatch.getMeta().getProfile().clear();
    mismatch.getMeta().addProfile("https://other.example/profile");
    assertThat(validator.validateResource(mismatch))
        .extracting(issue -> issue.code())
        .contains("PROFILE_MISMATCH");
    assertThat(validator.validateResource(new Organization().setId("Organization/o-1")))
        .extracting(issue -> issue.code())
        .containsExactly("RESOURCE_TYPE_UNSUPPORTED");

    final FhirProfileValidator profileOptional =
        new FhirProfileValidator(new ClinicalImportProperties(false, "https://example/fhir/"));
    assertThat(profileOptional.validateResource(validPatient(profileOptional, "p-2"))).isEmpty();
    assertThat(profileOptional.expectedProfile("Patient"))
        .isEqualTo("https://example/fhir/medassist-patient");

    final Patient invalid = new Patient();
    invalid.setId("Patient/p-3");
    invalid.getMeta().addProfile(validator.expectedProfile("Patient"));
    assertThat(validator.validateResource(invalid))
        .extracting(issue -> issue.message())
        .contains("Patient.birthDate is required", "Patient.gender is required");
  }

  @Test
  void importsAllSupportedResourceMappingsAndXmlPayloads() {
    final FhirProfileValidator validator = new FhirProfileValidator(PROPERTIES);
    final HapiFhirBundleImporter importer =
        new HapiFhirBundleImporter(FHIR, validator, new SafeHarborMapper(CLOCK));
    final Bundle bundle = validBundle(validator);

    final FhirBundleImportResult jsonResult =
        importer.importBundle(
            new FhirBundleImportDto(
                "fixture",
                FHIR.newJsonParser().encodeResourceToString(bundle),
                FhirPayloadFormat.JSON));
    assertThat(jsonResult.records()).hasSize(6);
    assertThat(jsonResult.records())
        .extracting(record -> record.resourceType())
        .containsExactlyInAnyOrder(
            "Patient", "Encounter", "Condition", "Medication", "Medication", "Observation");
    assertThat(jsonResult.quarantines()).isEmpty();

    final FhirBundleImportResult xmlResult =
        importer.importBundle(
            new FhirBundleImportDto(
                "fixture",
                FHIR.newXmlParser().encodeResourceToString(bundle),
                FhirPayloadFormat.XML));
    assertThat(xmlResult.acceptedCount()).isEqualTo(6);
    assertThat(xmlResult.quarantinedCount()).isZero();
    assertThat(jsonResult.records()).anyMatch(record -> record instanceof PatientRecord);
    assertThat(jsonResult.records()).anyMatch(record -> record instanceof EncounterRecord);
    assertThat(jsonResult.records()).anyMatch(record -> record instanceof ConditionRecord);
    assertThat(jsonResult.records()).anyMatch(record -> record instanceof MedicationRecord);
    assertThat(jsonResult.records()).anyMatch(record -> record instanceof ObservationRecord);
  }

  @Test
  void quarantinesParseProfileMissingEntryUnsupportedAndMappingFailures() {
    final FhirProfileValidator validator = new FhirProfileValidator(PROPERTIES);
    final HapiFhirBundleImporter importer =
        new HapiFhirBundleImporter(FHIR, validator, new SafeHarborMapper(CLOCK));

    final FhirBundleImportResult parseFailure =
        importer.importBundle(
            new FhirBundleImportDto("source", "not-json", FhirPayloadFormat.JSON));
    assertThat(parseFailure.quarantines())
        .singleElement()
        .satisfies(
            quarantine -> {
              assertThat(quarantine.stage()).isEqualTo(QuarantineStage.PARSE);
              assertThat(quarantine.reasonCode()).isEqualTo(QuarantineReason.PARSE_FAILED);
              assertThat(quarantine.reason()).doesNotContain("not-json");
            });

    final Bundle noResource = baseBundle(validator);
    noResource.addEntry();
    final FhirBundleImportResult missingEntry =
        importBundle(importer, noResource, FhirPayloadFormat.JSON);
    assertThat(missingEntry.quarantines())
        .singleElement()
        .satisfies(
            quarantine -> {
              assertThat(quarantine.reasonCode())
                  .isEqualTo(QuarantineReason.REQUIRED_FIELD_MISSING);
              assertThat(quarantine.resourceType()).isEqualTo("Bundle");
            });

    final Bundle unsupported = baseBundle(validator);
    unsupported.addEntry().setResource(new Organization().setId("Organization/o-1"));
    final FhirBundleImportResult unsupportedResult =
        importBundle(importer, unsupported, FhirPayloadFormat.JSON);
    assertThat(unsupportedResult.quarantines())
        .singleElement()
        .satisfies(
            quarantine -> {
              assertThat(quarantine.reasonCode())
                  .isEqualTo(QuarantineReason.RESOURCE_TYPE_UNSUPPORTED);
              assertThat(quarantine.stage()).isEqualTo(QuarantineStage.PROFILE_VALIDATION);
            });

    final Bundle mappingFailure = baseBundle(validator);
    final Encounter withoutType = new Encounter();
    withoutType.setId("Encounter/e-1");
    withoutType.setStatus(Encounter.EncounterStatus.FINISHED);
    withoutType.setClass_(new org.hl7.fhir.r4.model.Coding().setCode("AMB"));
    withoutType.setSubject(new Reference("Patient/p-1"));
    withoutType.setPeriod(new Period().setStart(date("2020-01-01")));
    addProfile(withoutType, validator);
    mappingFailure.addEntry().setResource(withoutType);
    final FhirBundleImportResult mappingResult =
        importBundle(importer, mappingFailure, FhirPayloadFormat.JSON);
    assertThat(mappingResult.quarantines())
        .singleElement()
        .satisfies(
            quarantine -> {
              assertThat(quarantine.reasonCode()).isEqualTo(QuarantineReason.MAPPING_FAILED);
              assertThat(quarantine.stage()).isEqualTo(QuarantineStage.MAPPING);
            });
  }

  @Test
  void mapsSafeHarborFieldsAndRejectsIncompleteResources() {
    final SafeHarborMapper mapper = new SafeHarborMapper(CLOCK);
    final Patient young = new Patient();
    young.setId("Patient/young");
    young.setBirthDate(date("2000-06-01"));
    young.setGender(Enumerations.AdministrativeGender.MALE);
    young.addAddress().setPostalCode("12");
    young.addExtension(
        new Extension("http://hl7.org/fhir/us/core/StructureDefinition/us-core-race")
            .setValue(new StringType("white")));
    assertThat(mapper.mapPatient(young))
        .satisfies(
            record -> {
              assertThat(record.ageBand()).isEqualTo("24");
              assertThat(record.zip3()).isNull();
              assertThat(record.race()).isEqualTo("white");
            });

    final Encounter encounter = new Encounter();
    encounter.setId("Encounter/e-1");
    encounter.setSubject(new Reference("Patient/p-1"));
    encounter.addType(
        new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding("sys", "visit", "visit")));
    encounter.setPeriod(new Period().setStart(date("2020-01-01")).setEnd(date("2020-01-03")));
    encounter
        .addReasonCode()
        .addCoding(new org.hl7.fhir.r4.model.Coding("sys", "reason", "reason"));
    assertThat(mapper.mapEncounter(encounter).endYear()).isEqualTo(2020);

    final Condition condition = new Condition();
    condition.setId("Condition/c-1");
    condition.setSubject(new Reference("Patient/p-1"));
    condition.setEncounter(new Reference("Encounter/e-1"));
    condition.setCode(
        new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding("sys", "c", null)));
    condition.getCode().setText("condition text");
    condition.setClinicalStatus(
        new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding("sys", "active", null)));
    condition.setOnset(new DateTimeType("2020-01-01T00:00:00Z"));
    assertThat(mapper.mapCondition(condition))
        .satisfies(
            record -> {
              assertThat(record.display()).isEqualTo("condition text");
              assertThat(record.onsetYear()).isEqualTo(2020);
              assertThat(record.encounterId()).isEqualTo("e-1");
            });

    final MedicationRequest request = medicationRequest();
    request.getDispenseRequest().setValidityPeriod(new Period().setEnd(date("2021-01-01")));
    assertThat(mapper.mapMedicationRequest(request).endYear()).isEqualTo(2021);

    final MedicationStatement statement = new MedicationStatement();
    statement.setId("MedicationStatement/m-1");
    statement.setSubject(new Reference("Patient/p-1"));
    statement.setMedication(code("rx", "med", "medicine"));
    statement.setEffective(new DateTimeType("2022-02-01T00:00:00Z"));
    statement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
    assertThat(mapper.mapMedicationStatement(statement).startYear()).isEqualTo(2022);

    final Observation textObservation = new Observation();
    textObservation.setId("Observation/o-2");
    textObservation.setSubject(new Reference("Patient/p-1"));
    textObservation.setCode(code("loinc", "x", null));
    textObservation.getCode().setText("text code");
    textObservation.setEffective(new DateTimeType("2023-02-01T00:00:00Z"));
    textObservation.setValue(new StringType("positive"));
    final ObservationRecord mapped = mapper.mapObservation(textObservation);
    assertThat(mapped.value()).isEqualTo("positive");
    assertThat(mapped.unit()).isNull();
    assertThat(mapped.display()).isEqualTo("text code");

    final Patient missingPatient = new Patient();
    missingPatient.setId("Patient/missing");
    assertThatThrownBy(() -> mapper.mapPatient(missingPatient))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> mapper.mapObservation(textObservation.setSubject(new Reference())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> mapper.mapObservation(textObservation.setEffective(new StringType("bad"))))
        .isInstanceOf(Error.class);
  }

  private static Bundle validBundle(final FhirProfileValidator validator) {
    final Bundle bundle = baseBundle(validator);
    bundle.addEntry().setResource(validPatient(validator, "p-1"));
    bundle.addEntry().setResource(validEncounter(validator));
    bundle.addEntry().setResource(validCondition(validator));
    bundle.addEntry().setResource(medicationRequest(validator));
    bundle.addEntry().setResource(medicationStatement(validator));
    bundle.addEntry().setResource(validObservation(validator));
    return bundle;
  }

  private static Bundle baseBundle(final FhirProfileValidator validator) {
    final Bundle bundle = new Bundle();
    bundle.setId("Bundle/b-1");
    bundle.setType(Bundle.BundleType.COLLECTION);
    addProfile(bundle, validator);
    return bundle;
  }

  private static Patient validPatient(final FhirProfileValidator validator, final String id) {
    final Patient patient = new Patient();
    patient.setId("Patient/" + id);
    patient.setBirthDate(date("1980-01-01"));
    patient.setGender(Enumerations.AdministrativeGender.MALE);
    addProfile(patient, validator);
    return patient;
  }

  private static Encounter validEncounter(final FhirProfileValidator validator) {
    final Encounter encounter = new Encounter();
    encounter.setId("Encounter/e-1");
    encounter.setStatus(Encounter.EncounterStatus.FINISHED);
    encounter.setClass_(new org.hl7.fhir.r4.model.Coding().setCode("AMB"));
    encounter.setSubject(new Reference("Patient/p-1"));
    encounter.addType(code("sys", "visit", "visit"));
    encounter.setPeriod(new Period().setStart(date("2020-01-01")));
    addProfile(encounter, validator);
    return encounter;
  }

  private static Condition validCondition(final FhirProfileValidator validator) {
    final Condition condition = new Condition();
    condition.setId("Condition/c-1");
    condition.setSubject(new Reference("Patient/p-1"));
    condition.setCode(code("sys", "condition", "condition"));
    condition.setClinicalStatus(code("sys", "active", "active"));
    addProfile(condition, validator);
    return condition;
  }

  private static MedicationRequest medicationRequest(final FhirProfileValidator validator) {
    final MedicationRequest request = medicationRequest();
    addProfile(request, validator);
    return request;
  }

  private static MedicationRequest medicationRequest() {
    final MedicationRequest request = new MedicationRequest();
    request.setId("MedicationRequest/mr-1");
    request.setSubject(new Reference("Patient/p-1"));
    request.setMedication(code("rx", "med", "medicine"));
    request.setAuthoredOn(date("2020-01-01"));
    request.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
    request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
    return request;
  }

  private static MedicationStatement medicationStatement(final FhirProfileValidator validator) {
    final MedicationStatement statement = new MedicationStatement();
    statement.setId("MedicationStatement/ms-1");
    statement.setSubject(new Reference("Patient/p-1"));
    statement.setMedication(code("rx", "med", "medicine"));
    statement.setEffective(new Period().setStart(date("2020-01-01")));
    statement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
    addProfile(statement, validator);
    return statement;
  }

  private static Observation validObservation(final FhirProfileValidator validator) {
    final Observation observation = new Observation();
    observation.setId("Observation/o-1");
    observation.setSubject(new Reference("Patient/p-1"));
    observation.setCode(code("loinc", "8480-6", "systolic"));
    observation.setEffective(new org.hl7.fhir.r4.model.DateTimeType("2020-01-01"));
    observation.setValue(new Quantity().setValue(120).setUnit("mmHg"));
    addProfile(observation, validator);
    return observation;
  }

  private static CodeableConcept code(
      final String system, final String code, final String display) {
    return new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding(system, code, display));
  }

  private static void addProfile(final Resource resource, final FhirProfileValidator validator) {
    resource.getMeta().addProfile(validator.expectedProfile(resource.getResourceType().name()));
  }

  private static FhirBundleImportResult importBundle(
      final HapiFhirBundleImporter importer, final Bundle bundle, final FhirPayloadFormat format) {
    final String payload =
        format == FhirPayloadFormat.JSON
            ? FHIR.newJsonParser().encodeResourceToString(bundle)
            : FHIR.newXmlParser().encodeResourceToString(bundle);
    return importer.importBundle(new FhirBundleImportDto("source", payload, format));
  }

  private static Date date(final String value) {
    return Date.from(Instant.parse(value + "T00:00:00Z"));
  }
}
