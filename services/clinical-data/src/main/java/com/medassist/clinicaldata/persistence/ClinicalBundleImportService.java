package com.medassist.clinicaldata.persistence;

import com.medassist.clinicaldata.fhir.FhirBundleImportDto;
import com.medassist.clinicaldata.fhir.FhirBundleImportResult;
import com.medassist.clinicaldata.fhir.HapiFhirBundleImporter;
import java.util.Objects;

/** Composes HAPI parsing/mapping with the explicit persistence boundary. */
public final class ClinicalBundleImportService {
  private final HapiFhirBundleImporter importer;
  private final ClinicalImportPersistencePort persistence;

  public ClinicalBundleImportService(
      final HapiFhirBundleImporter importer, final ClinicalImportPersistencePort persistence) {
    this.importer = Objects.requireNonNull(importer, "importer");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
  }

  public ClinicalImportPersistenceResult importBundle(final FhirBundleImportDto input) {
    Objects.requireNonNull(input, "input");
    final FhirBundleImportResult importResult = importer.importBundle(input);
    return persistence.persist(input.sourceId(), importResult);
  }
}
