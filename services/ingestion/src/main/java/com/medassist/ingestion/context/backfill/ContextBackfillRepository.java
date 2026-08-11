package com.medassist.ingestion.context.backfill;

import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence boundary for incremental context generation over existing chunks. */
public interface ContextBackfillRepository {
  List<ContextBackfillDocument> findPending(
      ContextualRetrievalMode mode, String promptVersion, int chunkLimit);

  void save(ContextBackfillWrite write);

  void enqueuePhiReview(UUID chunkId, PhiScanStatus status, Set<String> entityTypes);
}
