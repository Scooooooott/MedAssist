package com.medassist.retrieval.application;

import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationValidationResult;
import com.medassist.retrieval.application.model.RetrievedChunk;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CitationValidator {
  public List<CitationValidationResult> validate(
      final List<CitationCandidate> citations, final Collection<RetrievedChunk> chunks) {
    final Map<UUID, RetrievedChunk> chunksById =
        chunks.stream().collect(Collectors.toUnmodifiableMap(RetrievedChunk::chunkId, Function.identity()));
    return citations.stream().map(citation -> validateOne(citation, chunksById)).toList();
  }

  private CitationValidationResult validateOne(
      final CitationCandidate citation, final Map<UUID, RetrievedChunk> chunksById) {
    if (citation == null || citation.chunkId() == null) {
      return new CitationValidationResult(citation, false, "citation is missing a chunkId");
    }
    final RetrievedChunk chunk = chunksById.get(citation.chunkId());
    if (chunk == null) {
      return new CitationValidationResult(citation, false, "citation chunk was not retrieved");
    }
    if (!chunk.documentVersionId().equals(citation.documentVersionId())) {
      return new CitationValidationResult(citation, false, "citation documentVersionId does not match chunk");
    }
    if (!StringUtils.hasText(citation.quotedSpan())) {
      return new CitationValidationResult(citation, false, "citation quotedSpan is empty");
    }
    if (!chunk.text().contains(citation.quotedSpan())) {
      return new CitationValidationResult(citation, false, "citation quotedSpan was not found in chunk");
    }
    return new CitationValidationResult(citation, true, "quotedSpan exists in retrieved chunk");
  }
}
