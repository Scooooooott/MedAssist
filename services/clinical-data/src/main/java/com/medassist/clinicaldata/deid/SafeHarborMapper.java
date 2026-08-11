package com.medassist.clinicaldata.deid;

import com.medassist.clinicaldata.model.CodingValue;
import com.medassist.clinicaldata.model.ConditionRecord;
import com.medassist.clinicaldata.model.EncounterRecord;
import com.medassist.clinicaldata.model.MedicationRecord;
import com.medassist.clinicaldata.model.ObservationRecord;
import com.medassist.clinicaldata.model.PatientRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Type;

/** Maps HAPI resources to records that contain no exact date, full ZIP, or name. */
public final class SafeHarborMapper {
  private static final String RACE_EXTENSION =
      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race";
  private static final String ETHNICITY_EXTENSION =
      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity";
  private final Clock clock;

  public SafeHarborMapper(final Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public PatientRecord mapPatient(final Patient patient) {
    final Date birthDate = Objects.requireNonNull(patient.getBirthDate(), "birthDate");
    final LocalDate birth = birthDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    final int age = java.time.Period.between(birth, LocalDate.now(clock)).getYears();
    return new PatientRecord(
        patient.getIdElement().getIdPart(),
        birth.getYear(),
        age > 89 ? "90+" : Integer.toString(Math.max(age, 0)),
        patient.getGenderElement().getValueAsString(),
        extensionCode(patient, RACE_EXTENSION),
        extensionCode(patient, ETHNICITY_EXTENSION),
        zip3(patient));
  }

  public EncounterRecord mapEncounter(final Encounter encounter) {
    final Period period = Objects.requireNonNull(encounter.getPeriod(), "period");
    return new EncounterRecord(
        encounter.getIdElement().getIdPart(),
        patientId(encounter.getSubject()),
        coding(encounter.getTypeFirstRep()),
        year(period.getStart()),
        period.hasEnd() ? year(period.getEnd()) : null,
        encounter.hasReasonCode() ? coding(encounter.getReasonCodeFirstRep()) : null);
  }

  public ConditionRecord mapCondition(final Condition condition) {
    return new ConditionRecord(
        condition.getIdElement().getIdPart(),
        patientId(condition.getSubject()),
        condition.hasEncounter() ? referenceId(condition.getEncounter()) : null,
        coding(condition.getCode()),
        display(condition.getCode()),
        condition.hasOnset() ? year(condition.getOnset()) : null,
        condition.getClinicalStatus().getCodingFirstRep().getCode());
  }

  public MedicationRecord mapMedicationRequest(final MedicationRequest medication) {
    final Integer startYear = year(medication.getAuthoredOn());
    final org.hl7.fhir.r4.model.Period validity =
        medication.hasDispenseRequest() && medication.getDispenseRequest().hasValidityPeriod()
            ? medication.getDispenseRequest().getValidityPeriod()
            : null;
    return new MedicationRecord(
        medication.getIdElement().getIdPart(),
        patientId(medication.getSubject()),
        medication.hasEncounter() ? referenceId(medication.getEncounter()) : null,
        coding(medication.getMedicationCodeableConcept()),
        display(medication.getMedicationCodeableConcept()),
        startYear,
        validity == null || !validity.hasEnd() ? null : year(validity.getEnd()),
        medication.getStatusElement().getValueAsString());
  }

  public MedicationRecord mapMedicationStatement(final MedicationStatement medication) {
    final Type effective = medication.getEffective();
    final Period period = effective instanceof Period ? (Period) effective : null;
    return new MedicationRecord(
        medication.getIdElement().getIdPart(),
        patientId(medication.getSubject()),
        medication.hasContext() ? referenceId(medication.getContext()) : null,
        coding(medication.getMedicationCodeableConcept()),
        display(medication.getMedicationCodeableConcept()),
        period == null ? year(effective) : year(period.getStart()),
        period == null || !period.hasEnd() ? null : year(period.getEnd()),
        medication.getStatusElement().getValueAsString());
  }

  public ObservationRecord mapObservation(final Observation observation) {
    final Type value = Objects.requireNonNull(observation.getValue(), "value");
    final String unit = value instanceof Quantity ? ((Quantity) value).getUnit() : null;
    final String valueText =
        value instanceof Quantity
            ? ((Quantity) value).getValue() == null
                ? null
                : ((Quantity) value).getValue().toPlainString()
            : value.primitiveValue();
    return new ObservationRecord(
        observation.getIdElement().getIdPart(),
        patientId(observation.getSubject()),
        observation.hasEncounter() ? referenceId(observation.getEncounter()) : null,
        coding(observation.getCode()),
        display(observation.getCode()),
        valueText,
        unit,
        year(observation.getEffective()));
  }

  private static String zip3(final Patient patient) {
    if (!patient.hasAddress() || !patient.getAddressFirstRep().hasPostalCode()) {
      return null;
    }
    final String digits = patient.getAddressFirstRep().getPostalCode().replaceAll("[^0-9]", "");
    return digits.length() < 3 ? null : digits.substring(0, 3);
  }

  private static String extensionCode(final Patient patient, final String url) {
    if (!patient.hasExtension()) {
      return null;
    }
    return patient.getExtension().stream()
        .filter(extension -> url.equals(extension.getUrl()) && extension.hasValue())
        .map(extension -> extension.getValue().primitiveValue())
        .findFirst()
        .orElse(null);
  }

  private static CodingValue coding(final CodeableConcept concept) {
    Objects.requireNonNull(concept, "code");
    final Coding coding =
        concept.getCoding().stream()
            .filter(Coding::hasCode)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("coding.code is required"));
    return new CodingValue(
        coding.hasSystem() ? coding.getSystem() : null,
        coding.getCode(),
        coding.hasDisplay() ? coding.getDisplay() : null);
  }

  private static String display(final CodeableConcept concept) {
    final CodingValue value = coding(concept);
    return value.display() == null && concept.hasText() ? concept.getText() : value.display();
  }

  private static String patientId(final Reference reference) {
    return referenceId(reference);
  }

  private static String referenceId(final Reference reference) {
    if (reference == null
        || !reference.hasReference()
        || !reference.getReferenceElement().hasIdPart()) {
      throw new IllegalArgumentException("patient reference must contain a resource id");
    }
    return reference.getReferenceElement().getIdPart();
  }

  private static int year(final Date date) {
    if (date == null) {
      throw new IllegalArgumentException("date is required");
    }
    return date.toInstant().atZone(ZoneOffset.UTC).getYear();
  }

  private static int year(final Type type) {
    if (type instanceof DateTimeType) {
      return year(((DateTimeType) type).getValue());
    }
    if (type instanceof org.hl7.fhir.r4.model.DateType) {
      return year(((org.hl7.fhir.r4.model.DateType) type).getValue());
    }
    if (type instanceof Period) {
      return year(((Period) type).getStart());
    }
    throw new IllegalArgumentException("supported date or period is required");
  }
}
