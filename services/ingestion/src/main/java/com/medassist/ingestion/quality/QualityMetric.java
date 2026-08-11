package com.medassist.ingestion.quality;

/** Safe, aggregate metrics available to quality assertions. */
public enum QualityMetric {
  TOTAL_RECORD_COUNT {
    @Override
    double read(final QualitySnapshot snapshot) {
      return snapshot.totalRecordCount();
    }
  },
  ACCEPTANCE_RATE {
    @Override
    double read(final QualitySnapshot snapshot) {
      return snapshot.totalRecordCount() == 0
          ? 0.0
          : (double) snapshot.acceptedRecordCount() / snapshot.totalRecordCount();
    }
  },
  REJECTION_RATE {
    @Override
    double read(final QualitySnapshot snapshot) {
      return snapshot.totalRecordCount() == 0
          ? 0.0
          : (double) snapshot.rejectedRecordCount() / snapshot.totalRecordCount();
    }
  },
  RESIDUAL_PHI_COUNT {
    @Override
    double read(final QualitySnapshot snapshot) {
      return snapshot.residualPhiFindingCount();
    }
  },
  UNIQUE_CONTENT_HASH_COUNT {
    @Override
    double read(final QualitySnapshot snapshot) {
      return snapshot.contentHashes().size();
    }
  };

  abstract double read(QualitySnapshot snapshot);
}
