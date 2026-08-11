package com.medassist.clinicaldata.fhir;

import com.medassist.clinicaldata.config.ClinicalImportProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;

/** Explicit profile contract for the imported subset. HAPI supplies parsing and R4 types. */
public final class FhirProfileValidator {
  private final ClinicalImportProperties properties;

  public FhirProfileValidator(final ClinicalImportProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public List<FhirValidationIssue> validateBundle(final Bundle bundle) {
    Objects.requireNonNull(bundle, "bundle");
    final List<FhirValidationIssue> issues = new ArrayList<>();
    validateIdentity(bundle, "Bundle", issues);
    validateProfile(bundle, "Bundle", issues);
    if (!bundle.hasType()) {
      issues.add(new FhirValidationIssue("REQUIRED_FIELD_MISSING", "Bundle.type is required"));
    }
    if (!bundle.hasEntry() || bundle.getEntry().isEmpty()) {
      issues.add(
          new FhirValidationIssue(
              "REQUIRED_FIELD_MISSING", "Bundle.entry must contain at least one resource"));
    }
    return List.copyOf(issues);
  }

  public List<FhirValidationIssue> validateResource(final Resource resource) {
    Objects.requireNonNull(resource, "resource");
    final List<FhirValidationIssue> issues = new ArrayList<>();
    final String type = resource.getResourceType().name();
    if (!isSupported(resource)) {
      issues.add(
          new FhirValidationIssue(
              "RESOURCE_TYPE_UNSUPPORTED", "resource type is not importable: " + type));
      return List.copyOf(issues);
    }
    validateIdentity(resource, type, issues);
    validateProfile(resource, type, issues);
    switch (resource.getResourceType()) {
      case Patient -> validatePatient((Patient) resource, issues);
      case Encounter -> validateEncounter((Encounter) resource, issues);
      case Condition -> validateCondition((Condition) resource, issues);
      case MedicationRequest -> validateMedicationRequest((MedicationRequest) resource, issues);
      case MedicationStatement ->
          validateMedicationStatement((MedicationStatement) resource, issues);
      case Observation -> validateObservation((Observation) resource, issues);
      default -> throw new IllegalStateException("supported resource switch is incomplete");
    }
    return List.copyOf(issues);
  }

  public String expectedProfile(final String resourceType) {
    final String normalized = resourceType.toLowerCase(Locale.ROOT);
    return properties.profileBaseUrl() + "medassist-" + normalized;
  }

  private void validateIdentity(
      final Resource resource, final String type, final List<FhirValidationIssue> issues) {
    if (!resource.getIdElement().hasIdPart()) {
      issues.add(new FhirValidationIssue("REQUIRED_FIELD_MISSING", type + ".id is required"));
    }
  }

  private void validateProfile(
      final Resource resource, final String type, final List<FhirValidationIssue> issues) {
    if (!properties.requireProfile()) {
      return;
    }
    final String expected = expectedProfile(type);
    if (!resource.hasMeta() || resource.getMeta().getProfile().isEmpty()) {
      issues.add(
          new FhirValidationIssue(
              "PROFILE_MISSING", type + ".meta.profile is required; expected " + expected));
      return;
    }
    final boolean matches =
        resource.getMeta().getProfile().stream()
            .anyMatch(profile -> expected.equals(profile.getValueAsString()));
    if (!matches) {
      issues.add(
          new FhirValidationIssue(
              "PROFILE_MISMATCH", type + ".meta.profile must include " + expected));
    }
  }

  private static boolean isSupported(final Resource resource) {
    return switch (resource.getResourceType()) {
      case Patient, Encounter, Condition, MedicationRequest, MedicationStatement, Observation ->
          true;
      default -> false;
    };
  }

  private static void validatePatient(
      final Patient patient, final List<FhirValidationIssue> issues) {
    require(patient.hasBirthDate(), "Patient.birthDate is required", issues);
    require(patient.hasGender(), "Patient.gender is required", issues);
  }

  private static void validateEncounter(
      final Encounter encounter, final List<FhirValidationIssue> issues) {
    require(encounter.hasStatus(), "Encounter.status is required", issues);
    require(encounter.hasClass_(), "Encounter.class is required", issues);
    require(encounter.hasSubject(), "Encounter.subject is required", issues);
    require(
        encounter.hasPeriod() && encounter.getPeriod().hasStart(),
        "Encounter.period.start is required",
        issues);
  }

  private static void validateCondition(
      final Condition condition, final List<FhirValidationIssue> issues) {
    require(condition.hasSubject(), "Condition.subject is required", issues);
    require(condition.hasCode(), "Condition.code is required", issues);
    require(
        condition.hasCode()
            && condition.getCode().getCoding().stream().anyMatch(coding -> coding.hasCode()),
        "Condition.code.coding.code is required",
        issues);
    require(condition.hasClinicalStatus(), "Condition.clinicalStatus is required", issues);
  }

  private static void validateMedicationRequest(
      final MedicationRequest medication, final List<FhirValidationIssue> issues) {
    require(medication.hasSubject(), "MedicationRequest.subject is required", issues);
    require(medication.hasMedication(), "MedicationRequest.medication is required", issues);
    require(medication.hasAuthoredOn(), "MedicationRequest.authoredOn is required", issues);
    require(
        medication.hasMedicationCodeableConcept()
            && medication.getMedicationCodeableConcept().getCoding().stream()
                .anyMatch(coding -> coding.hasCode()),
        "MedicationRequest.medicationCodeableConcept.coding.code is required",
        issues);
  }

  private static void validateMedicationStatement(
      final MedicationStatement medication, final List<FhirValidationIssue> issues) {
    require(medication.hasSubject(), "MedicationStatement.subject is required", issues);
    require(medication.hasMedication(), "MedicationStatement.medication is required", issues);
    require(medication.hasEffective(), "MedicationStatement.effective is required", issues);
    require(
        medication.hasMedicationCodeableConcept()
            && medication.getMedicationCodeableConcept().getCoding().stream()
                .anyMatch(coding -> coding.hasCode()),
        "MedicationStatement.medicationCodeableConcept.coding.code is required",
        issues);
  }

  private static void validateObservation(
      final Observation observation, final List<FhirValidationIssue> issues) {
    require(observation.hasSubject(), "Observation.subject is required", issues);
    require(observation.hasCode(), "Observation.code is required", issues);
    require(
        observation.hasCode()
            && observation.getCode().getCoding().stream().anyMatch(coding -> coding.hasCode()),
        "Observation.code.coding.code is required",
        issues);
    require(observation.hasEffective(), "Observation.effective is required", issues);
    require(observation.hasValue(), "Observation.value[x] is required", issues);
  }

  private static void require(
      final boolean condition, final String message, final List<FhirValidationIssue> issues) {
    if (!condition) {
      issues.add(new FhirValidationIssue("REQUIRED_FIELD_MISSING", message));
    }
  }
}
