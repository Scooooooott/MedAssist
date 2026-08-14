package com.medassist.ingestion.pipeline.parse;

import com.medassist.domain.DocumentIR;
import com.medassist.domain.PhiEntity;
import com.medassist.domain.Section;
import com.medassist.domain.TableBlock;
import com.medassist.ingestion.discovery.DiscoveryTransientException;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.discovery.Sha256Hasher;
import com.medassist.ingestion.pipeline.mapping.SourceRangeMap;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enforces the fail-closed parser then de-identification boundary for one work item. */
public final class ParseAndDeidentifyProcessor {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final String DEFAULT_POLICY = "SAFE_HARBOR_SURROGATE";

  private final ParserClient parserClient;
  private final DeidentificationClient deidentificationClient;
  private final Duration parserTimeout;
  private final Duration deidentificationTimeout;
  private final String policy;
  private final Sha256Hasher sha256Hasher;

  public ParseAndDeidentifyProcessor(
      final ParserClient parserClient, final DeidentificationClient deidentificationClient) {
    this(parserClient, deidentificationClient, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT, DEFAULT_POLICY);
  }

  public ParseAndDeidentifyProcessor(
      final ParserClient parserClient,
      final DeidentificationClient deidentificationClient,
      final Duration timeout,
      final String policy) {
    this(parserClient, deidentificationClient, timeout, timeout, policy);
  }

  public ParseAndDeidentifyProcessor(
      final ParserClient parserClient,
      final DeidentificationClient deidentificationClient,
      final Duration parserTimeout,
      final Duration deidentificationTimeout,
      final String policy) {
    this.parserClient = Objects.requireNonNull(parserClient, "parserClient");
    this.deidentificationClient =
        Objects.requireNonNull(deidentificationClient, "deidentificationClient");
    requireTimeout(parserTimeout, "parserTimeout");
    requireTimeout(deidentificationTimeout, "deidentificationTimeout");
    this.parserTimeout = parserTimeout;
    this.deidentificationTimeout = deidentificationTimeout;
    this.policy = requireText(policy, "policy");
    this.sha256Hasher = new Sha256Hasher();
  }

  public ParseAndDeidentifyState process(final IngestionWorkItem workItem) {
    Objects.requireNonNull(workItem, "workItem");
    final ObjectDiscoveryResult discovery = workItem.discoveryResult();
    try {
      if (!sha256Hasher.hash(discovery.object()).equalsIgnoreCase(discovery.currentFingerprint())) {
        return quarantine(workItem, FailureStage.PARSE, "object content changed since discovery");
      }
    } catch (final DiscoveryTransientException exception) {
      return quarantine(workItem, FailureStage.PARSE, "object consistency check failed");
    }
    final ParserResponse parserResponse;
    try {
      parserResponse =
          parserClient.parse(
              new ParserRequest(
                  discovery.object().storageUri(),
                  discovery.object().mimeType(),
                  discovery.object().sourceId(),
                  discovery.object().metadata(),
                  parserTimeout));
    } catch (final ParserTransientException exception) {
      return quarantine(workItem, FailureStage.PARSE, "parser transient failure");
    } catch (final ParserPermanentException exception) {
      return quarantine(workItem, FailureStage.PARSE, "parser permanent failure");
    } catch (final ParserException exception) {
      return quarantine(workItem, FailureStage.PARSE, "parser failure");
    } catch (final RuntimeException exception) {
      return quarantine(workItem, FailureStage.PARSE, "parser failure");
    }

    if (parserResponse == null) {
      return quarantine(workItem, FailureStage.PARSE, "parser returned no response");
    }
    if (parserResponse.status() == ParseStatus.FAILED) {
      return quarantine(workItem, FailureStage.PARSE, "parser reported failure");
    }
    if (parserResponse.document() == null || isEmpty(parserResponse.document())) {
      return quarantine(workItem, FailureStage.PARSE, "parser returned empty document");
    }

    try {
      final DeidentifiedDocument transformed =
          transform(parserResponse.document(), workItem.documentVersionId().toString());
      final ProcessingStatus status =
          parserResponse.status() == ParseStatus.PARTIAL
              ? ProcessingStatus.PARTIAL
              : ProcessingStatus.SUCCEEDED;
      return new ParseAndDeidentifyState(
          workItem,
          transformed.document(),
          transformed.phiTypeCounts(),
          transformed.policyVersion(),
          parserResponse.warnings(),
          status,
          FailureStage.NONE,
          "");
    } catch (final DeidentificationTransientException exception) {
      return quarantine(
          workItem, FailureStage.DEIDENTIFICATION, "de-identification transient failure");
    } catch (final DeidentificationPermanentException exception) {
      return quarantine(
          workItem, FailureStage.DEIDENTIFICATION, "de-identification permanent failure");
    } catch (final DeidentificationException exception) {
      return quarantine(workItem, FailureStage.DEIDENTIFICATION, "de-identification failure");
    } catch (final RuntimeException exception) {
      return quarantine(workItem, FailureStage.DEIDENTIFICATION, "de-identification failure");
    }
  }

  private DeidentifiedDocument transform(final DocumentIR document, final String documentKey)
      throws DeidentificationException {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    final PolicyVersion policyVersion = new PolicyVersion();
    final Map<String, String> sourceRangeMappings = new LinkedHashMap<>();
    final List<Section> sections = new ArrayList<>();
    for (final Section section : document.sections()) {
      sections.add(
          transformSection(section, documentKey, counts, policyVersion, sourceRangeMappings));
    }

    final List<TableBlock> tables = new ArrayList<>();
    for (final TableBlock table : document.tables()) {
      tables.add(transformTable(table, documentKey, counts, policyVersion, sourceRangeMappings));
    }
    final Map<String, String> metadata =
        transformMap(document.metadata(), documentKey, counts, policyVersion);
    metadata.putAll(sourceRangeMappings);
    return new DeidentifiedDocument(
        new DocumentIR(sections, tables, metadata), counts, policyVersion.value());
  }

  private Section transformSection(
      final Section section,
      final String documentKey,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion,
      final Map<String, String> sourceRangeMappings)
      throws DeidentificationException {
    final String heading = deidentify(section.heading(), documentKey, counts, policyVersion);
    final MappedText mappedText =
        deidentifyMapped(section.text(), section.sourceRange(), documentKey, counts, policyVersion);
    sourceRangeMappings.put(
        SourceRangeMap.metadataKey(SourceRangeMap.sectionTextField(section.path())),
        mappedText.mapping().serialize());
    final List<Section> children = new ArrayList<>();
    for (final Section child : section.children()) {
      children.add(
          transformSection(child, documentKey, counts, policyVersion, sourceRangeMappings));
    }
    return new Section(
        section.path(),
        heading,
        section.level(),
        mappedText.text(),
        children,
        section.sourceRange());
  }

  private TableBlock transformTable(
      final TableBlock table,
      final String documentKey,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion,
      final Map<String, String> sourceRangeMappings)
      throws DeidentificationException {
    final String caption = deidentify(table.caption(), documentKey, counts, policyVersion);
    final Map<String, String> transformedHeaders = new LinkedHashMap<>();
    for (final String header : table.headers()) {
      transformedHeaders.put(header, deidentify(header, documentKey, counts, policyVersion));
    }
    final List<Map<String, String>> rows = new ArrayList<>();
    for (final Map<String, String> row : table.rows()) {
      final Map<String, String> transformedRow = new LinkedHashMap<>();
      for (final Map.Entry<String, String> cell : row.entrySet()) {
        final String transformedKey =
            transformedHeaders.containsKey(cell.getKey())
                ? transformedHeaders.get(cell.getKey())
                : deidentify(cell.getKey(), documentKey, counts, policyVersion);
        transformedRow.put(
            transformedKey, deidentify(cell.getValue(), documentKey, counts, policyVersion));
      }
      rows.add(transformedRow);
    }
    final MappedText mappedLinearizedText =
        deidentifyMapped(
            table.linearizedText(), table.sourceRange(), documentKey, counts, policyVersion);
    if (!table.linearizedText().isEmpty()) {
      sourceRangeMappings.put(
          SourceRangeMap.metadataKey(
              SourceRangeMap.tableTextField(
                  table.sectionPath(), table.sourceRange().start(), table.sourceRange().end())),
          mappedLinearizedText.mapping().serialize());
    }
    return new TableBlock(
        table.sectionPath(),
        caption,
        List.copyOf(transformedHeaders.values()),
        rows,
        mappedLinearizedText.text(),
        table.sourceRange());
  }

  private Map<String, String> transformMap(
      final Map<String, String> source,
      final String documentKey,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion)
      throws DeidentificationException {
    final Map<String, String> transformed = new LinkedHashMap<>();
    for (final Map.Entry<String, String> entry : source.entrySet()) {
      transformed.put(
          entry.getKey(), deidentify(entry.getValue(), documentKey, counts, policyVersion));
    }
    return transformed;
  }

  private String deidentify(
      final String text,
      final String documentKey,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion)
      throws DeidentificationException {
    final DeidentificationResponse response = anonymize(text, documentKey);
    recordResponse(response, counts, policyVersion);
    return response.text();
  }

  private MappedText deidentifyMapped(
      final String text,
      final com.medassist.domain.SourceRange sourceRange,
      final String documentKey,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion)
      throws DeidentificationException {
    final DeidentificationResponse response = anonymize(text, documentKey);
    recordResponse(response, counts, policyVersion);
    try {
      return new MappedText(
          response.text(),
          buildSourceRangeMap(text, response.text(), response.entities(), sourceRange));
    } catch (final IllegalArgumentException exception) {
      throw new DeidentificationPermanentException(
          "de-identification output cannot be mapped to original text", exception);
    }
  }

  private DeidentificationResponse anonymize(final String text, final String documentKey)
      throws DeidentificationException {
    final DeidentificationResponse response =
        deidentificationClient.anonymize(
            new DeidentificationRequest(text, policy, documentKey, deidentificationTimeout));
    if (response == null) {
      throw new DeidentificationPermanentException("de-identification returned no response");
    }
    return response;
  }

  private void recordResponse(
      final DeidentificationResponse response,
      final Map<String, Integer> counts,
      final PolicyVersion policyVersion)
      throws DeidentificationException {
    if (policyVersion.value() == null) {
      policyVersion.set(response.policyVersion());
    } else if (!policyVersion.value().equals(response.policyVersion())) {
      throw new DeidentificationPermanentException("inconsistent policy version");
    }
    for (final PhiEntity entity : response.entities()) {
      counts.merge(entity.entityType(), 1, Integer::sum);
    }
  }

  private static SourceRangeMap buildSourceRangeMap(
      final String original,
      final String transformed,
      final List<PhiEntity> entities,
      final com.medassist.domain.SourceRange sourceRange) {
    final List<PhiSpan> spans =
        entities.stream()
            .sorted(Comparator.comparingInt(PhiEntity::start))
            .map(entity -> new PhiSpan(entity.start(), entity.end()))
            .toList();
    final List<PhiSpan> merged = new ArrayList<>();
    for (final PhiSpan span : spans) {
      if (span.start() < 0 || span.end() > original.length() || span.end() <= span.start()) {
        throw new IllegalArgumentException("entity offsets are outside the original field");
      }
      if (!merged.isEmpty() && span.start() <= merged.get(merged.size() - 1).end()) {
        final PhiSpan previous = merged.remove(merged.size() - 1);
        merged.add(new PhiSpan(previous.start(), Math.max(previous.end(), span.end())));
      } else {
        merged.add(span);
      }
    }

    final List<SourceRangeMap.Segment> segments = new ArrayList<>();
    int originalCursor = 0;
    int transformedCursor = 0;
    for (int index = 0; index < merged.size(); index++) {
      final PhiSpan entity = merged.get(index);
      appendUnchanged(
          original,
          transformed,
          originalCursor,
          entity.start(),
          transformedCursor,
          segments,
          sourceRange);
      transformedCursor += entity.start() - originalCursor;
      final int nextOriginalStart =
          index + 1 < merged.size() ? merged.get(index + 1).start() : original.length();
      final String anchor = original.substring(entity.end(), nextOriginalStart);
      final int replacementEnd =
          anchor.isEmpty() ? transformed.length() : transformed.indexOf(anchor, transformedCursor);
      if (replacementEnd < transformedCursor) {
        throw new IllegalArgumentException("de-identification changed non-PHI text");
      }
      if (replacementEnd > transformedCursor) {
        segments.add(
            new SourceRangeMap.Segment(
                transformedCursor,
                replacementEnd,
                sourceRange.start() + entity.start(),
                sourceRange.start() + entity.end()));
      }
      transformedCursor = replacementEnd;
      originalCursor = entity.end();
    }
    appendUnchanged(
        original,
        transformed,
        originalCursor,
        original.length(),
        transformedCursor,
        segments,
        sourceRange);
    return new SourceRangeMap(transformed.length(), segments);
  }

  private static void appendUnchanged(
      final String original,
      final String transformed,
      final int originalStart,
      final int originalEnd,
      final int transformedStart,
      final List<SourceRangeMap.Segment> segments,
      final com.medassist.domain.SourceRange sourceRange) {
    final String unchanged = original.substring(originalStart, originalEnd);
    final int transformedEnd = transformedStart + unchanged.length();
    if (transformedEnd > transformed.length()
        || !unchanged.equals(transformed.substring(transformedStart, transformedEnd))) {
      throw new IllegalArgumentException("de-identification changed non-PHI text");
    }
    if (!unchanged.isEmpty()) {
      segments.add(
          new SourceRangeMap.Segment(
              transformedStart,
              transformedEnd,
              sourceRange.start() + originalStart,
              sourceRange.start() + originalEnd));
    }
  }

  private static boolean isEmpty(final DocumentIR document) {
    return document.sections().isEmpty() && document.tables().isEmpty();
  }

  private static ParseAndDeidentifyState quarantine(
      final IngestionWorkItem workItem, final FailureStage stage, final String reason) {
    return new ParseAndDeidentifyState(
        workItem, null, Map.of(), "", List.of(), ProcessingStatus.QUARANTINED, stage, reason);
  }

  private static String requireText(final String value, final String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static void requireTimeout(final Duration timeout, final String name) {
    Objects.requireNonNull(timeout, name + " must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private record DeidentifiedDocument(
      DocumentIR document, Map<String, Integer> phiTypeCounts, String policyVersion) {}

  private record MappedText(String text, SourceRangeMap mapping) {}

  private record PhiSpan(int start, int end) {}

  private static final class PolicyVersion {
    private String value;

    String value() {
      return value;
    }

    void set(final String value) {
      this.value = value;
    }
  }
}
