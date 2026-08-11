package com.medassist.ingestion.context.backfill;

import com.medassist.domain.Chunk;
import com.medassist.ingestion.context.ContextDocument;
import java.util.List;
import java.util.Objects;

/** One persisted document version and the clean chunks still needing a context mode. */
public record ContextBackfillDocument(ContextDocument document, List<Chunk> chunks) {
  public ContextBackfillDocument {
    Objects.requireNonNull(document, "document");
    chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    if (chunks.isEmpty()
        || chunks.stream()
            .anyMatch(chunk -> !document.documentVersionId().equals(chunk.documentVersionId()))) {
      throw new IllegalArgumentException("backfill chunks must belong to one document version");
    }
  }
}
