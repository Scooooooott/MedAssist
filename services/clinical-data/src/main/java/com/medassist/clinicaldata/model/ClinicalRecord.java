package com.medassist.clinicaldata.model;

/** Base type for de-identified records that may be persisted by a later adapter. */
public sealed interface ClinicalRecord
    permits PatientRecord, EncounterRecord, ConditionRecord, MedicationRecord, ObservationRecord {
  String resourceType();

  String resourceId();
}
