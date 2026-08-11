package com.medassist.ingestion.batch.steps.store;

import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.pipeline.store.IndexingPersistenceRequest;

/** Builds the already-governed persistence request for one durable stage item. */
@FunctionalInterface
public interface IndexingPersistenceRequestFactory {
  IndexingPersistenceRequest create(DurableStageItem item);
}
