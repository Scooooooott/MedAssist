package com.medassist.ingestion.chunking;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import java.util.List;
import java.util.UUID;

public interface Chunker {
  List<Chunk> chunk(
      UUID documentVersionId, String documentTitle, DocumentIR ir, ChunkingOptions options);
}
