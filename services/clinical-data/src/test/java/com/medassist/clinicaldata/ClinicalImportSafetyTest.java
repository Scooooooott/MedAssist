package com.medassist.clinicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.medassist.clinicaldata.config.ClinicalImportProperties;
import com.medassist.clinicaldata.deid.SafeHarborMapper;
import com.medassist.clinicaldata.fhir.FhirBundleImportDto;
import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.fhir.FhirPayloadFormat;
import com.medassist.clinicaldata.fhir.FhirProfileValidator;
import com.medassist.clinicaldata.fhir.HapiFhirBundleImporter;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import com.medassist.clinicaldata.quarantine.QuarantineReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.Test;

class ClinicalImportSafetyTest {
  private static final ClinicalImportProperties PROPERTIES = ClinicalImportProperties.defaults();
  private static final FhirContext FHIR = FhirContext.forR4();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void validatesRequiredProfileAndResourceFields() {
    final FhirProfileValidator validator = new FhirProfileValidator(PROPERTIES);
    final Patient patient = new Patient();
    patient.setId("Patient/p-1");

    assertThat(validator.validateResource(patient))
        .extracting(issue -> issue.code())
        .contains("PROFILE_MISSING", "REQUIRED_FIELD_MISSING");

    patient.setBirthDate(Date.from(Instant.parse("1930-01-01T00:00:00Z")));
    patient.setGender(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.MALE);
    patient.getMeta().addProfile(validator.expectedProfile("Patient"));
    assertThat(validator.validateResource(patient)).isEmpty();
  }

  @Test
  void quarantinesInvalidResourceWithoutDiscardingValidEntries() {
    final FhirProfileValidator validator = new FhirProfileValidator(PROPERTIES);
    final HapiFhirBundleImporter importer =
        new HapiFhirBundleImporter(FHIR, validator, new SafeHarborMapper(CLOCK));
    final Bundle bundle = new Bundle();
    bundle.setId("Bundle/b-1");
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.getMeta().addProfile(validator.expectedProfile("Bundle"));

    final Patient valid = new Patient();
    valid.setId("Patient/p-1");
    valid.setBirthDate(Date.from(Instant.parse("1930-01-01T00:00:00Z")));
    valid.setGender(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.MALE);
    valid.getMeta().addProfile(validator.expectedProfile("Patient"));
    bundle.addEntry().setResource(valid);

    final Patient invalid = new Patient();
    invalid.setId("Patient/p-2");
    invalid.getMeta().addProfile(validator.expectedProfile("Patient"));
    bundle.addEntry().setResource(invalid);

    final FhirBundleImportResult result =
        importer.importBundle(
            new FhirBundleImportDto(
                "fixture",
                FHIR.newJsonParser().encodeResourceToString(bundle),
                FhirPayloadFormat.JSON));

    assertThat(result.records()).hasSize(1);
    assertThat(result.quarantines()).hasSize(1);
    assertThat(result.quarantines().getFirst().reasonCode())
        .isEqualTo(QuarantineReason.REQUIRED_FIELD_MISSING);
  }

  @Test
  void mapsPatientToSafeHarborFields() {
    final Patient patient = new Patient();
    patient.setId("Patient/p-1");
    patient.setBirthDate(Date.from(Instant.parse("1930-06-01T00:00:00Z")));
    patient.setGender(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.FEMALE);
    patient.addAddress().setPostalCode("12345-6789");

    final PatientRecord mapped = new SafeHarborMapper(CLOCK).mapPatient(patient);

    assertThat(mapped.birthYear()).isEqualTo(1930);
    assertThat(mapped.ageBand()).isEqualTo("90+");
    assertThat(mapped.zip3()).isEqualTo("123");
    assertThat(mapped.gender()).isEqualTo("female");
  }

  @Test
  void mapsObservationQuantityWithoutExactTimestamp() {
    final Observation observation = new Observation();
    observation.setId("Observation/o-1");
    observation.setSubject(new org.hl7.fhir.r4.model.Reference("Patient/p-1"));
    observation.setCode(
        new org.hl7.fhir.r4.model.CodeableConcept()
            .addCoding(new org.hl7.fhir.r4.model.Coding("http://loinc.org", "8480-6", "systolic")));
    observation.setEffective(new org.hl7.fhir.r4.model.DateTimeType("2024-03-15"));
    observation.setValue(new Quantity().setValue(120).setUnit("mmHg"));

    final ObservationRecord mapped = new SafeHarborMapper(CLOCK).mapObservation(observation);

    assertThat(mapped.value()).isEqualTo("120");
    assertThat(mapped.unit()).isEqualTo("mmHg");
    assertThat(mapped.observationYear()).isEqualTo(2024);
  }
}
