package com.medassist.clinicaldata.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.medassist.clinicaldata.deid.SafeHarborMapper;
import com.medassist.clinicaldata.model.ClinicalRecord;
import com.medassist.clinicaldata.quarantine.QuarantineReason;
import com.medassist.clinicaldata.quarantine.QuarantineRecord;
import com.medassist.clinicaldata.quarantine.QuarantineStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Service;

/** Independent, resource-skipping importer. Persistence is intentionally a later adapter. */
@Service
public final class HapiFhirBundleImporter {
  private final FhirContext fhirContext;
  private final FhirProfileValidator validator;
  private final SafeHarborMapper mapper;

  public HapiFhirBundleImporter(
      final FhirContext fhirContext,
      final FhirProfileValidator validator,
      final SafeHarborMapper mapper) {
    this.fhirContext = Objects.requireNonNull(fhirContext, "fhirContext");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public FhirBundleImportResult importBundle(final FhirBundleImportDto input) {
    Objects.requireNonNull(input, "input");
    final Bundle bundle;
    try {
      final IParser parser =
          input.format() == FhirPayloadFormat.JSON
              ? fhirContext.newJsonParser()
              : fhirContext.newXmlParser();
      bundle = parser.parseResource(Bundle.class, input.payload());
    } catch (RuntimeException exception) {
      return new FhirBundleImportResult(
          List.of(),
          List.of(
              quarantine(
                  input.sourceId(),
                  "Bundle",
                  "unknown",
                  QuarantineStage.PARSE,
                  QuarantineReason.PARSE_FAILED,
                  "FHIR payload could not be parsed as a Bundle")));
    }

    final List<FhirValidationIssue> bundleIssues = validator.validateBundle(bundle);
    if (!bundleIssues.isEmpty()) {
      return new FhirBundleImportResult(
          List.of(),
          List.of(
              quarantine(
                  input.sourceId(),
                  "Bundle",
                  resourceId(bundle),
                  QuarantineStage.PROFILE_VALIDATION,
                  reasonCode(bundleIssues.get(0)),
                  messages(bundleIssues))));
    }

    final List<ClinicalRecord> records = new ArrayList<>();
    final List<QuarantineRecord> quarantines = new ArrayList<>();
    for (final Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasResource()) {
        quarantines.add(
            quarantine(
                input.sourceId(),
                "Unknown",
                "unknown",
                QuarantineStage.PROFILE_VALIDATION,
                QuarantineReason.REQUIRED_FIELD_MISSING,
                "Bundle.entry.resource is required"));
        continue;
      }
      importResource(input.sourceId(), entry.getResource(), records, quarantines);
    }
    return new FhirBundleImportResult(records, quarantines);
  }

  private void importResource(
      final String sourceId,
      final Resource resource,
      final List<ClinicalRecord> records,
      final List<QuarantineRecord> quarantines) {
    final List<FhirValidationIssue> issues = validator.validateResource(resource);
    if (!issues.isEmpty()) {
      quarantines.add(
          quarantine(
              sourceId,
              resource.getResourceType().name(),
              resourceId(resource),
              QuarantineStage.PROFILE_VALIDATION,
              reasonCode(issues.get(0)),
              messages(issues)));
      return;
    }
    try {
      records.add(map(resource));
    } catch (RuntimeException exception) {
      quarantines.add(
          quarantine(
              sourceId,
              resource.getResourceType().name(),
              resourceId(resource),
              QuarantineStage.MAPPING,
              QuarantineReason.MAPPING_FAILED,
              "validated FHIR resource could not be mapped to the Safe Harbor model"));
    }
  }

  private ClinicalRecord map(final Resource resource) {
    return switch (resource.getResourceType()) {
      case Patient -> mapper.mapPatient((org.hl7.fhir.r4.model.Patient) resource);
      case Encounter -> mapper.mapEncounter((org.hl7.fhir.r4.model.Encounter) resource);
      case Condition -> mapper.mapCondition((org.hl7.fhir.r4.model.Condition) resource);
      case MedicationRequest ->
          mapper.mapMedicationRequest((org.hl7.fhir.r4.model.MedicationRequest) resource);
      case MedicationStatement ->
          mapper.mapMedicationStatement((org.hl7.fhir.r4.model.MedicationStatement) resource);
      case Observation -> mapper.mapObservation((org.hl7.fhir.r4.model.Observation) resource);
      default -> throw new IllegalArgumentException("unsupported resource type");
    };
  }

  private static QuarantineRecord quarantine(
      final String sourceId,
      final String resourceType,
      final String resourceId,
      final QuarantineStage stage,
      final QuarantineReason reasonCode,
      final String reason) {
    return new QuarantineRecord(sourceId, resourceType, resourceId, stage, reasonCode, reason);
  }

  private static String resourceId(final Resource resource) {
    return resource.getIdElement().hasIdPart() ? resource.getIdElement().getIdPart() : "unknown";
  }

  private static QuarantineReason reasonCode(final FhirValidationIssue issue) {
    try {
      return QuarantineReason.valueOf(issue.code());
    } catch (IllegalArgumentException exception) {
      return QuarantineReason.REQUIRED_FIELD_MISSING;
    }
  }

  private static String messages(final List<FhirValidationIssue> issues) {
    return String.join("; ", issues.stream().map(FhirValidationIssue::message).toList());
  }
}
