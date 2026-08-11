package com.medassist.retrieval.application;

import com.medassist.retrieval.application.model.CitationAssertion;
import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationCoverageResult;
import com.medassist.retrieval.application.model.CitationValidationReport;
import com.medassist.retrieval.application.model.CitationValidationResult;
import com.medassist.retrieval.application.model.CitationValidationStatus;
import com.medassist.retrieval.application.model.RetrievedChunk;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CitationValidator {
  public static final double DEFAULT_MINIMUM_COVERAGE = 0.8d;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern ASSERTION_BOUNDARY = Pattern.compile("(?<=[.!?。！？])\\s+|\\R+");

  private final double minimumCoverage;

  public CitationValidator() {
    this(DEFAULT_MINIMUM_COVERAGE);
  }

  public CitationValidator(final double minimumCoverage) {
    requireCoverageThreshold(minimumCoverage);
    this.minimumCoverage = minimumCoverage;
  }

  public List<CitationValidationResult> validate(
      final List<CitationCandidate> citations, final Collection<RetrievedChunk> chunks) {
    final Map<UUID, RetrievedChunk> chunksById = indexChunks(chunks);
    if (citations == null) {
      return List.of();
    }
    return citations.stream().map(citation -> validateOne(citation, chunksById)).toList();
  }

  public CitationValidationReport validateWithCoverage(
      final List<CitationAssertion> assertions, final Collection<RetrievedChunk> chunks) {
    return validateWithCoverage(assertions, chunks, minimumCoverage);
  }

  public CitationValidationReport validateWithCoverage(
      final List<CitationAssertion> assertions,
      final Collection<RetrievedChunk> chunks,
      final double requestedMinimumCoverage) {
    requireCoverageThreshold(requestedMinimumCoverage);
    final Map<UUID, RetrievedChunk> chunksById = indexChunks(chunks);
    final List<CitationValidationResult> results = new ArrayList<>();
    final Set<String> freshnessWarnings = new LinkedHashSet<>();
    int assertionCount = 0;
    int coveredAssertionCount = 0;

    if (assertions != null) {
      for (final CitationAssertion assertion : assertions) {
        if (assertion == null || !StringUtils.hasText(assertion.text())) {
          continue;
        }
        assertionCount++;
        final List<CitationValidationResult> assertionResults =
            validate(assertion.citations(), chunksById.values());
        results.addAll(assertionResults);
        if (assertionResults.stream().anyMatch(CitationValidationResult::valid)) {
          coveredAssertionCount++;
        }
        assertionResults.stream()
            .filter(CitationValidationResult::valid)
            .map(CitationValidationResult::message)
            .filter(message -> message.contains("freshness warning"))
            .forEach(freshnessWarnings::add);
      }
    }

    final CitationCoverageResult coverage =
        new CitationCoverageResult(assertionCount, coveredAssertionCount, requestedMinimumCoverage);
    final CitationValidationStatus status = determineStatus(results, coverage);
    return new CitationValidationReport(results, coverage, status, List.copyOf(freshnessWarnings));
  }

  public CitationCoverageResult calculateCoverage(
      final List<CitationAssertion> assertions, final Collection<RetrievedChunk> chunks) {
    return validateWithCoverage(assertions, chunks).coverage();
  }

  public CitationCoverageResult calculateCoverage(
      final List<CitationAssertion> assertions,
      final Collection<RetrievedChunk> chunks,
      final double requestedMinimumCoverage) {
    return validateWithCoverage(assertions, chunks, requestedMinimumCoverage).coverage();
  }

  public List<String> splitIntoAssertions(final String answer) {
    if (!StringUtils.hasText(answer)) {
      return List.of();
    }
    return ASSERTION_BOUNDARY
        .splitAsStream(answer)
        .map(String::strip)
        .filter(StringUtils::hasText)
        .toList();
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
    if (chunk.documentVersionId() == null
        || citation.documentVersionId() == null
        || !Objects.equals(chunk.documentVersionId(), citation.documentVersionId())) {
      return new CitationValidationResult(
          citation, false, "citation documentVersionId does not match chunk");
    }
    if (!StringUtils.hasText(citation.quotedSpan())) {
      return new CitationValidationResult(citation, false, "citation quotedSpan is empty");
    }
    if (!matchesRawChunkText(chunk.text(), citation.quotedSpan())) {
      return new CitationValidationResult(
          citation, false, "citation quotedSpan was not found in chunk");
    }
    return new CitationValidationResult(citation, true, successMessage(chunk));
  }

  private Map<UUID, RetrievedChunk> indexChunks(final Collection<RetrievedChunk> chunks) {
    final Map<UUID, RetrievedChunk> chunksById = new LinkedHashMap<>();
    if (chunks == null) {
      return chunksById;
    }
    for (final RetrievedChunk chunk : chunks) {
      if (chunk != null && chunk.chunkId() != null) {
        chunksById.putIfAbsent(chunk.chunkId(), chunk);
      }
    }
    return chunksById;
  }

  private CitationValidationStatus determineStatus(
      final List<CitationValidationResult> results, final CitationCoverageResult coverage) {
    if (coverage.assertionCount() == 0 || coverage.sufficient()) {
      return results.stream().anyMatch(result -> !result.valid())
          ? CitationValidationStatus.INVALID_CITATION
          : CitationValidationStatus.VALID;
    }
    if (results.stream().noneMatch(CitationValidationResult::valid)) {
      return CitationValidationStatus.INVALID_CITATION;
    }
    return CitationValidationStatus.INSUFFICIENT_COVERAGE;
  }

  private String successMessage(final RetrievedChunk chunk) {
    final String warning = freshnessWarning(chunk);
    return warning.isEmpty()
        ? "quotedSpan exists in retrieved chunk"
        : "quotedSpan exists in retrieved chunk; " + warning;
  }

  private String freshnessWarning(final RetrievedChunk chunk) {
    final List<String> reasons = new ArrayList<>();
    if ("SUPERSEDED".equalsIgnoreCase(chunk.documentStatus())) {
      reasons.add("document status is SUPERSEDED");
    }
    if (chunk.stale() || "EXPIRED".equalsIgnoreCase(chunk.documentStatus())) {
      reasons.add("evidence is expired or stale");
    }
    return reasons.isEmpty() ? "" : "freshness warning: " + String.join("; ", reasons);
  }

  private boolean matchesRawChunkText(final String rawText, final String quotedSpan) {
    if (!StringUtils.hasText(rawText)) {
      return false;
    }
    final String normalizedText = normalizeForMatch(rawText);
    final String normalizedQuote = normalizeForMatch(quotedSpan);
    return !normalizedQuote.isEmpty() && normalizedText.contains(normalizedQuote);
  }

  private String normalizeForMatch(final String value) {
    final String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    final StringBuilder result = new StringBuilder(normalized.length());
    for (int offset = 0; offset < normalized.length(); ) {
      final int codePoint = normalized.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
        result.append(' ');
        continue;
      }
      final String punctuation = normalizedPunctuation(codePoint);
      if (punctuation == null) {
        result.appendCodePoint(codePoint);
      } else {
        result.append(punctuation);
      }
    }
    return WHITESPACE.matcher(result).replaceAll(" ").strip();
  }

  private String normalizedPunctuation(final int codePoint) {
    return switch (codePoint) {
      case 0x2018, 0x2019, 0x02BC -> "'";
      case 0x201C, 0x201D, 0x00AB, 0x00BB -> "\"";
      case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2212 -> "-";
      case 0x2026 -> "...";
      default -> null;
    };
  }

  private void requireCoverageThreshold(final double threshold) {
    if (!Double.isFinite(threshold) || threshold < 0.0d || threshold > 1.0d) {
      throw new IllegalArgumentException("coverage threshold must be between 0.0 and 1.0");
    }
  }
}
