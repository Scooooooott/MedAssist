package com.medassist.ingestion.context.backfill;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Context and vector payload for one clean, already de-identified chunk. */
public record ContextBackfillChunkWrite(UUID chunkId, String contextPrefix, List<Float> embedding) {
  public ContextBackfillChunkWrite {
    Objects.requireNonNull(chunkId, "chunkId");
    contextPrefix = Objects.requireNonNull(contextPrefix, "contextPrefix");
    embedding = List.copyOf(Objects.requireNonNull(embedding, "embedding"));
  }
}
