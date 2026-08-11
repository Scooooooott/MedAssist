package com.medassist.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.retrieval.application.model.CitationAssertion;
import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationValidationStatus;
import com.medassist.retrieval.application.model.RetrievedChunk;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {
  private static final UUID CHUNK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private final CitationValidator validator = new CitationValidator();

  @Test
  void preservesLegacyValidationForExactRawText() {
    final var result = validator.validate(List.of(candidate("source text")), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isTrue();
              assertThat(value.message()).isEqualTo("quotedSpan exists in retrieved chunk");
            });
  }

  @Test
  void neverAcceptsContextPrefixOrMetadataAsEvidence() {
    final RetrievedChunk chunk =
        chunk(
            "The source sentence.", "ACTIVE", false, Map.of("context_prefix", "fabricated claim"));

    final var result = validator.validate(List.of(candidate("fabricated claim")), List.of(chunk));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message()).contains("not found in chunk");
            });
  }

  @Test
  void matchesNormalizedWhitespaceAndCase() {
    final RetrievedChunk chunk = chunk("The Patient has\n a right to access.");

    final var result =
        validator.validate(
            List.of(candidate(" the patient   HAS a right to access. ")), List.of(chunk));

    assertThat(result).singleElement().extracting(value -> value.valid()).isEqualTo(true);
  }

  @Test
  void matchesLimitedUnicodePunctuationNormalization() {
    final RetrievedChunk chunk = chunk("Use “short-term” treatment…");

    final var result =
        validator.validate(List.of(candidate("use \"short-term\" treatment...")), List.of(chunk));

    assertThat(result).singleElement().extracting(value -> value.valid()).isEqualTo(true);
  }

  @Test
  void rejectsMissingChunkWithExplicitReason() {
    final var result = validator.validate(List.of(candidate("source text")), List.of(otherChunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message()).isEqualTo("citation chunk was not retrieved");
            });
  }

  @Test
  void rejectsMissingChunkIdWithExplicitReason() {
    final var result =
        validator.validate(
            List.of(new CitationCandidate(null, VERSION_ID, "text", "direct")), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message()).isEqualTo("citation is missing a chunkId");
            });
  }

  @Test
  void rejectsDocumentVersionMismatchBeforeMatchingText() {
    final CitationCandidate citation =
        new CitationCandidate(CHUNK_ID, otherVersion(), "source text", "direct");

    final var result = validator.validate(List.of(citation), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message())
                  .isEqualTo("citation documentVersionId does not match chunk");
            });
  }

  @Test
  void rejectsEmptyQuotedSpanWithExplicitReason() {
    final var result = validator.validate(List.of(candidate(" \n\t ")), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message()).isEqualTo("citation quotedSpan is empty");
            });
  }

  @Test
  void rejectsUnmatchedQuotedSpanWithExplicitReason() {
    final var result =
        validator.validate(List.of(candidate("invented statement")), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isFalse();
              assertThat(value.message()).isEqualTo("citation quotedSpan was not found in chunk");
            });
  }

  @Test
  void keepsSupersededEvidenceValidAndAddsFreshnessWarning() {
    final var result =
        validator.validate(
            List.of(candidate("source text")),
            List.of(chunk("source text", "SUPERSEDED", false, Map.of())));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isTrue();
              assertThat(value.message()).contains("freshness warning", "SUPERSEDED");
            });
  }

  @Test
  void keepsStaleEvidenceValidAndAddsFreshnessWarning() {
    final var result =
        validator.validate(
            List.of(candidate("source text")),
            List.of(chunk("source text", "ACTIVE", true, Map.of())));

    assertThat(result)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.valid()).isTrue();
              assertThat(value.message()).contains("freshness warning", "expired or stale");
            });
  }

  @Test
  void doesNotWarnForActiveFreshEvidence() {
    final var result = validator.validate(List.of(candidate("source text")), List.of(chunk()));

    assertThat(result)
        .singleElement()
        .extracting(value -> value.message())
        .isEqualTo("quotedSpan exists in retrieved chunk");
  }

  @Test
  void calculatesFullAssertionCoverageAtConfiguredThreshold() {
    final List<CitationAssertion> assertions =
        List.of(
            new CitationAssertion("First assertion.", List.of(candidate("source text"))),
            new CitationAssertion("Second assertion.", List.of(otherCandidate("other source"))));

    final var report =
        validator.validateWithCoverage(assertions, List.of(chunk(), otherChunk()), 1.0d);

    assertThat(report.status()).isEqualTo(CitationValidationStatus.VALID);
    assertThat(report.coverage().coverage()).isEqualTo(1.0d);
    assertThat(report.coverage().sufficient()).isTrue();
  }

  @Test
  void distinguishesInsufficientCoverageFromInvalidCitation() {
    final List<CitationAssertion> assertions =
        List.of(
            new CitationAssertion("Covered assertion.", List.of(candidate("source text"))),
            new CitationAssertion("Uncovered assertion.", List.of()));

    final var report = validator.validateWithCoverage(assertions, List.of(chunk()), 0.8d);

    assertThat(report.status()).isEqualTo(CitationValidationStatus.INSUFFICIENT_COVERAGE);
    assertThat(report.hasInvalidCitations()).isFalse();
    assertThat(report.coverage().coverage()).isEqualTo(0.5d);
  }

  @Test
  void reportsInvalidCitationSeparatelyWhenNoCitationCanCoverAssertions() {
    final List<CitationAssertion> assertions =
        List.of(new CitationAssertion("Unsupported assertion.", List.of(candidate("invented"))));

    final var report = validator.validateWithCoverage(assertions, List.of(chunk()), 0.8d);

    assertThat(report.status()).isEqualTo(CitationValidationStatus.INVALID_CITATION);
    assertThat(report.hasInvalidCitations()).isTrue();
    assertThat(report.insufficientCoverage()).isFalse();
  }

  @Test
  void thresholdCanBeChangedPerCoverageCalculation() {
    final List<CitationAssertion> assertions =
        List.of(
            new CitationAssertion("Covered assertion.", List.of(candidate("source text"))),
            new CitationAssertion("Uncovered assertion.", List.of()));

    final var report = validator.validateWithCoverage(assertions, List.of(chunk()), 0.5d);

    assertThat(report.coverage().minimumCoverage()).isEqualTo(0.5d);
    assertThat(report.coverage().sufficient()).isTrue();
    assertThat(report.status()).isEqualTo(CitationValidationStatus.VALID);
  }

  @Test
  void exposesReproducibleSentenceSplitting() {
    final List<String> assertions =
        validator.splitIntoAssertions("First claim. Second claim!\nThird claim?");

    assertThat(assertions).containsExactly("First claim.", "Second claim!", "Third claim?");
  }

  @Test
  void rejectsInvalidCoverageThreshold() {
    assertThatThrownBy(() -> validator.calculateCoverage(List.of(), List.of(), 1.1d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("coverage threshold must be between 0.0 and 1.0");
  }

  private CitationCandidate candidate(final String quotedSpan) {
    return new CitationCandidate(CHUNK_ID, VERSION_ID, quotedSpan, "direct");
  }

  private CitationCandidate otherCandidate(final String quotedSpan) {
    return new CitationCandidate(
        UUID.fromString("00000000-0000-0000-0000-000000000003"), VERSION_ID, quotedSpan, "direct");
  }

  private RetrievedChunk chunk() {
    return chunk("source text", "ACTIVE", false, Map.of());
  }

  private RetrievedChunk chunk(final String text) {
    return chunk(text, "ACTIVE", false, Map.of());
  }

  private RetrievedChunk chunk(
      final String text,
      final String documentStatus,
      final boolean stale,
      final Map<String, String> metadata) {
    return new RetrievedChunk(
        CHUNK_ID,
        VERSION_ID,
        0,
        "Recommendations",
        text,
        2,
        0,
        11,
        0.9,
        "VECTOR",
        "COSINE",
        "GUIDELINE",
        "Publisher",
        "Title",
        "v1",
        LocalDate.of(2025, 1, 1),
        documentStatus,
        stale,
        null,
        null,
        null,
        null,
        null,
        metadata);
  }

  private RetrievedChunk otherChunk() {
    return new RetrievedChunk(
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        VERSION_ID,
        1,
        "Other",
        "other source",
        2,
        0,
        12,
        0.8,
        "LEXICAL",
        "BM25",
        "GUIDELINE",
        "Publisher",
        "Title",
        "v1",
        LocalDate.of(2025, 1, 1),
        "ACTIVE",
        false,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }

  private UUID otherVersion() {
    return UUID.fromString("00000000-0000-0000-0000-000000000099");
  }
}
