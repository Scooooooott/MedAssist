package com.medassist.clinicaldata.fhir;

import com.medassist.clinicaldata.model.ClinicalRecord;
import com.medassist.clinicaldata.quarantine.QuarantineRecord;
import java.util.List;

public record FhirBundleImportResult(
    List<ClinicalRecord> records, List<QuarantineRecord> quarantines) {
  public FhirBundleImportResult {
    records = List.copyOf(records);
    quarantines = List.copyOf(quarantines);
  }

  public int acceptedCount() {
    return records.size();
  }

  public int quarantinedCount() {
    return quarantines.size();
  }
}
