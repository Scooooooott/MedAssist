package com.medassist.ingestion.chunking;

import com.medassist.domain.Chunk;
import com.medassist.domain.ContentDomain;
import com.medassist.domain.SourceRange;
import com.medassist.domain.TableBlock;
import com.medassist.ingestion.pipeline.mapping.SourceRangeMap;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChunkingSupport {
  private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\R\\s*\\R+");

  private ChunkingSupport() {}

  static List<TextSpan> paragraphOrSentenceUnits(
      final String text,
      final SourceRange sourceRange,
      final TokenCounter tokenCounter,
      final ChunkingOptions options) {
    final List<TextSpan> paragraphs = paragraphs(text, sourceRange);
    final List<TextSpan> units = new ArrayList<>();
    for (final TextSpan paragraph : paragraphs) {
      if (tokenCounter.count(paragraph.text()) <= options.maxTokens()) {
        units.add(paragraph);
      } else {
        for (final TextSpan sentence :
            sentenceUnits(paragraph.text(), paragraph.range(), tokenCounter, options)) {
          units.add(sentence.rebase(text, paragraph.localStart()));
        }
      }
    }
    return units;
  }

  static List<TextSpan> sentenceUnits(
      final String text,
      final SourceRange sourceRange,
      final TokenCounter tokenCounter,
      final ChunkingOptions options) {
    final BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
    iterator.setText(text);
    final List<TextSpan> sentences = new ArrayList<>();
    int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
      final TextSpan sentence = trimmedSpan(text, start, end, sourceRange);
      if (sentence == null) {
        continue;
      }
      if (tokenCounter.count(sentence.text()) > options.maxTokens()) {
        throw new UnchunkableContentException("single sentence exceeds maxTokens");
      }
      sentences.add(sentence);
    }
    if (sentences.isEmpty() && !text.isBlank()) {
      final TextSpan whole = trimmedSpan(text, 0, text.length(), sourceRange);
      if (whole != null && tokenCounter.count(whole.text()) > options.maxTokens()) {
        throw new UnchunkableContentException("text without a sentence boundary exceeds maxTokens");
      }
      if (whole != null) {
        sentences.add(whole);
      }
    }
    return sentences;
  }

  static List<TextSpan> groupUnits(
      final List<TextSpan> units, final TokenCounter tokenCounter, final ChunkingOptions options) {
    final List<TextSpan> chunks = new ArrayList<>();
    int cursor = 0;
    while (cursor < units.size()) {
      final int first = cursor;
      int end = cursor;
      TextSpan current = null;
      while (end < units.size()) {
        final TextSpan candidate = spanBetween(units, first, end + 1);
        if (tokenCounter.count(candidate.text()) > options.maxTokens()) {
          break;
        }
        current = candidate;
        end++;
        if (tokenCounter.count(current.text()) >= options.targetTokens()) {
          break;
        }
      }
      if (current == null) {
        throw new UnchunkableContentException("unable to form a chunk within maxTokens");
      }
      chunks.add(current);

      int next = end;
      if (options.overlapTokens() > 0 && end < units.size()) {
        int overlapTokens = 0;
        int overlapStart = end;
        for (int index = end - 1; index >= first; index--) {
          final int unitTokens = tokenCounter.count(units.get(index).text());
          if (overlapTokens + unitTokens > options.overlapTokens()) {
            break;
          }
          overlapTokens += unitTokens;
          overlapStart = index;
        }
        if (overlapStart > first) {
          next = overlapStart;
        }
      }
      cursor = next > first ? next : end;
    }
    return chunks;
  }

  static TextSpan mergeSpans(final List<TextSpan> spans, final int first, final int exclusiveEnd) {
    final TextSpan start = spans.get(first);
    final TextSpan end = spans.get(exclusiveEnd - 1);
    return new TextSpan(
        new SourceRange(start.range().start(), end.range().end()),
        start.localStart(),
        end.localEnd(),
        start.sourceText());
  }

  static List<TextSpan> paragraphs(final String text, final SourceRange sourceRange) {
    final List<TextSpan> paragraphs = new ArrayList<>();
    final Matcher matcher = PARAGRAPH_BREAK.matcher(text);
    int cursor = 0;
    while (matcher.find()) {
      addTrimmed(paragraphs, text, cursor, matcher.start(), sourceRange);
      cursor = matcher.end();
    }
    addTrimmed(paragraphs, text, cursor, text.length(), sourceRange);
    return paragraphs;
  }

  static Chunk createChunk(
      final UUID documentVersionId,
      final int ordinal,
      final String sectionPath,
      final String text,
      final SourceRange sourceRange,
      final String breadcrumb,
      final String strategyId,
      final TokenCounter tokenCounter) {
    final Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("content_domain", ContentDomain.PUBLIC.name());
    metadata.put("section_path", sectionPath);
    metadata.put("source_char_start", Long.toString(sourceRange.start()));
    metadata.put("source_char_end", Long.toString(sourceRange.end()));
    metadata.put("breadcrumb", breadcrumb);
    metadata.put("chunking_strategy_id", strategyId);
    return new Chunk(
        UUID.randomUUID(),
        documentVersionId,
        ordinal,
        sectionPath,
        text,
        tokenCounter.count(text),
        sourceRange,
        metadata);
  }

  static SourceRange mappedRange(
      final TextSpan span, final Map<String, String> metadata, final String fieldId) {
    final String encoded = metadata.get(SourceRangeMap.metadataKey(fieldId));
    if (encoded == null) {
      return span.range();
    }
    return SourceRangeMap.parse(encoded).rangeFor(span.localStart(), span.localEnd());
  }

  static SourceRange mappedFullRange(
      final String text,
      final SourceRange fallback,
      final Map<String, String> metadata,
      final String fieldId) {
    final String encoded = metadata.get(SourceRangeMap.metadataKey(fieldId));
    if (encoded == null || text.isEmpty()) {
      return fallback;
    }
    return SourceRangeMap.parse(encoded).rangeFor(0, text.length());
  }

  static String breadcrumb(
      final String documentTitle, final List<String> parents, final String heading) {
    final List<String> values = new ArrayList<>();
    if (documentTitle != null && !documentTitle.isBlank()) {
      values.add(documentTitle.trim());
    }
    for (final String parent : parents) {
      if (parent != null && !parent.isBlank()) {
        values.add(parent.trim());
      }
    }
    if (heading != null && !heading.isBlank()) {
      values.add(heading.trim());
    }
    return String.join(" > ", values);
  }

  static List<String> tableTexts(
      final TableBlock table, final TokenCounter tokenCounter, final ChunkingOptions options) {
    final String prefix = table.caption().isBlank() ? "" : table.caption() + System.lineSeparator();
    if (!table.linearizedText().isBlank() || table.rows().isEmpty()) {
      final String text =
          prefix + (table.linearizedText().isBlank() ? toMarkdown(table) : table.linearizedText());
      ensureTableFits(text, tokenCounter, options, "linearized table");
      return List.of(text);
    }
    final String header = markdownHeader(table);
    final List<String> result = new ArrayList<>();
    final List<String> currentRows = new ArrayList<>();
    for (final Map<String, String> row : table.rows()) {
      final String rowText = markdownRow(table, row);
      final String candidate =
          prefix
              + header
              + System.lineSeparator()
              + String.join(System.lineSeparator(), append(currentRows, rowText));
      if (!currentRows.isEmpty() && tokenCounter.count(candidate) > options.maxTokens()) {
        result.add(
            prefix
                + header
                + System.lineSeparator()
                + String.join(System.lineSeparator(), currentRows));
        currentRows.clear();
      }
      final String singleRow = prefix + header + System.lineSeparator() + rowText;
      ensureTableFits(singleRow, tokenCounter, options, "single table row");
      currentRows.add(rowText);
    }
    if (!currentRows.isEmpty()) {
      result.add(
          prefix
              + header
              + System.lineSeparator()
              + String.join(System.lineSeparator(), currentRows));
    }
    return result;
  }

  private static TextSpan spanBetween(
      final List<TextSpan> units, final int first, final int exclusiveEnd) {
    final TextSpan start = units.get(first);
    final TextSpan end = units.get(exclusiveEnd - 1);
    return new TextSpan(
        new SourceRange(start.range().start(), end.range().end()),
        start.localStart(),
        end.localEnd(),
        start.sourceText());
  }

  private static void addTrimmed(
      final List<TextSpan> output,
      final String text,
      final int start,
      final int end,
      final SourceRange sourceRange) {
    final TextSpan span = trimmedSpan(text, start, end, sourceRange);
    if (span != null) {
      output.add(span);
    }
  }

  private static TextSpan trimmedSpan(
      final String text, final int start, final int end, final SourceRange sourceRange) {
    int left = start;
    int right = end;
    while (left < right && Character.isWhitespace(text.charAt(left))) {
      left++;
    }
    while (right > left && Character.isWhitespace(text.charAt(right - 1))) {
      right--;
    }
    if (left == right) {
      return null;
    }
    return new TextSpan(
        new SourceRange(sourceRange.start() + left, sourceRange.start() + right),
        left,
        right,
        text);
  }

  private static String markdownHeader(final TableBlock table) {
    final String header = "| " + String.join(" | ", table.headers()) + " |";
    final String separator =
        "| " + String.join(" | ", table.headers().stream().map(column -> "---").toList()) + " |";
    return header + System.lineSeparator() + separator;
  }

  private static String markdownRow(final TableBlock table, final Map<String, String> row) {
    return "| "
        + String.join(" | ", table.headers().stream().map(h -> row.getOrDefault(h, "")).toList())
        + " |";
  }

  private static String toMarkdown(final TableBlock table) {
    final List<String> lines = new ArrayList<>();
    lines.add(markdownHeader(table));
    for (final Map<String, String> row : table.rows()) {
      lines.add(markdownRow(table, row));
    }
    return String.join(System.lineSeparator(), lines);
  }

  private static List<String> append(final List<String> values, final String value) {
    final List<String> result = new ArrayList<>(values);
    result.add(value);
    return result;
  }

  private static void ensureTableFits(
      final String text,
      final TokenCounter tokenCounter,
      final ChunkingOptions options,
      final String kind) {
    if (tokenCounter.count(text) > options.maxTokens()) {
      throw new UnchunkableContentException(kind + " exceeds maxTokens");
    }
  }

  static final class UnchunkableContentException extends RuntimeException {
    UnchunkableContentException(final String message) {
      super(message);
    }
  }

  static final class TextSpan {
    private final SourceRange range;
    private final int localStart;
    private final int localEnd;
    private final String sourceText;

    TextSpan(
        final SourceRange range,
        final int localStart,
        final int localEnd,
        final String sourceText) {
      this.range = range;
      this.localStart = localStart;
      this.localEnd = localEnd;
      this.sourceText = sourceText;
    }

    String text() {
      return sourceText.substring(localStart, localEnd);
    }

    SourceRange range() {
      return range;
    }

    int localStart() {
      return localStart;
    }

    int localEnd() {
      return localEnd;
    }

    String sourceText() {
      return sourceText;
    }

    TextSpan rebase(final String newSourceText, final int offset) {
      return new TextSpan(range, localStart + offset, localEnd + offset, newSourceText);
    }
  }
}
