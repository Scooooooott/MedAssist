package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.CitationSummary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Fail-closed verifier for model citations against the current transient evidence. */
public final class StructuredDraftVerifier implements DraftVerifier {
  @Override
  public VerificationResult verify(final GeneratedDraft draft, final AgentState state) {
    Objects.requireNonNull(draft, "draft");
    Objects.requireNonNull(state, "state");
    final RuntimeSafetyEvidence evidence = state.runtimeSafetyEvidence();
    final int candidateCount = evidence.chunks().size();
    final CitationSummary emptySummary = new CitationSummary(candidateCount, 0, false);
    if (candidateCount == 0) {
      final StructuredDraftParser.ParsedDraft parsed;
      try {
        parsed = StructuredDraftParser.parse(draft.structuredResponse());
      } catch (final InvalidDraftFormatException exception) {
        return VerificationResult.retry(emptySummary);
      }
      if (!parsed.citations().isEmpty()) {
        return VerificationResult.reject(emptySummary);
      }
      final boolean containsAggregateValue =
          state.aggregationColumns().stream()
              .map(SafeAggregationColumn::value)
              .filter(value -> !value.isBlank())
              .anyMatch(parsed.answer()::contains);
      final CitationSummary summary = new CitationSummary(0, 0, containsAggregateValue);
      return containsAggregateValue
          ? VerificationResult.accepted(summary)
          : VerificationResult.reject(summary);
    }

    final StructuredDraftParser.ParsedDraft parsed;
    try {
      parsed = StructuredDraftParser.parse(draft.structuredResponse());
    } catch (final InvalidDraftFormatException exception) {
      return VerificationResult.retry(emptySummary);
    }

    final Map<UUID, RuntimeEvidenceChunk> evidenceById = new HashMap<>();
    evidence.chunks().forEach(chunk -> evidenceById.put(chunk.chunkId(), chunk));
    final Set<UUID> candidateIds =
        state.candidateChunks().stream()
            .map(candidate -> candidate.chunkId())
            .collect(java.util.stream.Collectors.toSet());
    final Set<UUID> seenCitations = new HashSet<>();
    int validCount = 0;
    boolean invalidCitation = parsed.citations().isEmpty();
    for (final StructuredDraftParser.StructuredCitation citation : parsed.citations()) {
      final RuntimeEvidenceChunk chunk = evidenceById.get(citation.chunkId());
      final boolean valid =
          seenCitations.add(citation.chunkId())
              && candidateIds.contains(citation.chunkId())
              && chunk != null
              && containsNormalizedContiguousSpan(chunk.content(), citation.quotedSpan());
      if (valid) {
        validCount++;
      } else {
        invalidCitation = true;
      }
    }
    final CitationSummary summary =
        new CitationSummary(candidateCount, validCount, validCount > 0 && !invalidCitation);
    return invalidCitation
        ? VerificationResult.retry(summary)
        : VerificationResult.accepted(summary);
  }

  private static boolean containsNormalizedContiguousSpan(
      final String content, final String quotedSpan) {
    final String normalizedSpan = normalizeCitationText(quotedSpan);
    return !normalizedSpan.isEmpty() && normalizeCitationText(content).contains(normalizedSpan);
  }

  private static String normalizeCitationText(final String value) {
    final StringBuilder normalized = new StringBuilder(value.length());
    boolean pendingSpace = false;
    for (int offset = 0; offset < value.length(); ) {
      final int codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isCitationWhitespace(codePoint)) {
        pendingSpace = normalized.length() > 0;
        continue;
      }
      if (pendingSpace) {
        normalized.append(' ');
        pendingSpace = false;
      }
      appendNormalizedPunctuation(normalized, codePoint);
    }
    return normalized.toString().toLowerCase(Locale.ROOT);
  }

  private static boolean isCitationWhitespace(final int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static void appendNormalizedPunctuation(
      final StringBuilder normalized, final int codePoint) {
    switch (codePoint) {
      case 0x2018, 0x2019, 0x201A, 0x201B -> normalized.append('\'');
      case 0x201C, 0x201D, 0x201E, 0x201F -> normalized.append('"');
      case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0xFE58, 0xFE63 ->
          normalized.append('-');
      case 0x2026 -> normalized.append("...");
      default ->
          normalized.appendCodePoint(
              codePoint >= 0xFF01 && codePoint <= 0xFF5E ? codePoint - 0xFEE0 : codePoint);
    }
  }
}
