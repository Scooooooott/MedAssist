package com.medassist.clinicaldata.persistence;

import com.medassist.clinicaldata.fhir.FhirBundleImportResult;

/** Explicit persistence boundary for an already parsed and Safe Harbor mapped bundle. */
public interface ClinicalImportPersistencePort {
  ClinicalImportPersistenceResult persist(String sourceId, FhirBundleImportResult importResult);
}
