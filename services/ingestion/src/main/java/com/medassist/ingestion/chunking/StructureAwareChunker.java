package com.medassist.ingestion.chunking;

import com.medassist.domain.Chunk;
import com.medassist.domain.ContentDomain;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import com.medassist.domain.TableBlock;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StructureAwareChunker implements Chunker {
  private static final Logger LOGGER = LoggerFactory.getLogger(StructureAwareChunker.class);
  private final TokenCounter tokenCounter;

  public StructureAwareChunker() {
    this(new SimpleTokenCounter());
  }

  public StructureAwareChunker(final TokenCounter tokenCounter) {
    this.tokenCounter = tokenCounter;
  }

  @Override
  public List<Chunk> chunk(
      final UUID documentVersionId,
      final String documentTitle,
      final DocumentIR ir,
      final ChunkingOptions options) {
    final List<Chunk> chunks = new ArrayList<>();
    for (final Section section : ir.sections()) {
      appendSectionChunks(chunks, documentVersionId, documentTitle, List.of(), section, options);
    }
    for (final TableBlock table : ir.tables()) {
      appendTableChunks(chunks, documentVersionId, documentTitle, table, options);
    }
    LOGGER.info(
        "chunk_stats documentVersionId={} chunks={} totalTokens={}",
        documentVersionId,
        chunks.size(),
        chunks.stream().mapToInt(Chunk::tokenCount).sum());
    return chunks;
  }

  private void appendSectionChunks(
      final List<Chunk> chunks,
      final UUID documentVersionId,
      final String documentTitle,
      final List<String> parents,
      final Section section,
      final ChunkingOptions options) {
    final List<String> breadcrumb = new ArrayList<>(parents);
    if (!section.heading().isBlank()) {
      breadcrumb.add(section.heading());
    }

    final String prefix = String.join(" > ", prependTitle(documentTitle, breadcrumb));
    final String text =
        prefix.isBlank() ? section.text() : prefix + System.lineSeparator() + section.text();
    final int tokenCount = tokenCounter.count(text);
    if (tokenCount <= options.maxTokens()) {
      chunks.add(
          createChunk(
              documentVersionId, chunks.size(), section.path(), text, section.sourceRange()));
    } else {
      for (final TextPart part :
          splitBySentence(section.text(), section.sourceRange(), prefix, options)) {
        final String partText =
            prefix.isBlank() ? part.text() : prefix + System.lineSeparator() + part.text();
        chunks.add(
            createChunk(documentVersionId, chunks.size(), section.path(), partText, part.range()));
      }
    }

    for (final Section child : section.children()) {
      appendSectionChunks(chunks, documentVersionId, documentTitle, breadcrumb, child, options);
    }
  }

  private List<String> prependTitle(final String documentTitle, final List<String> breadcrumb) {
    final List<String> values = new ArrayList<>();
    if (documentTitle != null && !documentTitle.isBlank()) {
      values.add(documentTitle);
    }
    values.addAll(breadcrumb);
    return values;
  }

  private void appendTableChunks(
      final List<Chunk> chunks,
      final UUID documentVersionId,
      final String documentTitle,
      final TableBlock table,
      final ChunkingOptions options) {
    final String title =
        documentTitle == null || documentTitle.isBlank()
            ? ""
            : documentTitle + System.lineSeparator();
    final String caption =
        table.caption().isBlank() ? "" : table.caption() + System.lineSeparator();
    if (!table.linearizedText().isBlank() || table.rows().isEmpty()) {
      final String text =
          title
              + caption
              + (table.linearizedText().isBlank() ? toMarkdown(table) : table.linearizedText());
      if (tokenCounter.count(text) > options.maxTokens()) {
        throw new UnchunkableContentException("linearized table exceeds maxTokens");
      }
      chunks.add(
          createChunk(
              documentVersionId, chunks.size(), table.sectionPath(), text, table.sourceRange()));
      return;
    }
    final String header = markdownHeader(table);
    final List<String> currentRows = new ArrayList<>();
    for (final Map<String, String> row : table.rows()) {
      final String rowText = markdownRow(table, row);
      final String candidate =
          title
              + caption
              + header
              + System.lineSeparator()
              + String.join(System.lineSeparator(), append(currentRows, rowText));
      if (!currentRows.isEmpty() && tokenCounter.count(candidate) > options.maxTokens()) {
        chunks.add(
            createChunk(
                documentVersionId,
                chunks.size(),
                table.sectionPath(),
                title
                    + caption
                    + header
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), currentRows),
                table.sourceRange()));
        currentRows.clear();
      }
      if (tokenCounter.count(title + caption + header + System.lineSeparator() + rowText)
          > options.maxTokens()) {
        throw new UnchunkableContentException("single table row exceeds maxTokens");
      }
      currentRows.add(rowText);
    }
    if (!currentRows.isEmpty()) {
      chunks.add(
          createChunk(
              documentVersionId,
              chunks.size(),
              table.sectionPath(),
              title
                  + caption
                  + header
                  + System.lineSeparator()
                  + String.join(System.lineSeparator(), currentRows),
              table.sourceRange()));
    }
  }

  private String markdownHeader(final TableBlock table) {
    final String header = "| " + String.join(" | ", table.headers()) + " |";
    final String separator =
        "| " + String.join(" | ", table.headers().stream().map(column -> "---").toList()) + " |";
    return header + System.lineSeparator() + separator;
  }

  private String markdownRow(final TableBlock table, final Map<String, String> row) {
    return "| "
        + String.join(" | ", table.headers().stream().map(h -> row.getOrDefault(h, "")).toList())
        + " |";
  }

  private List<String> append(final List<String> values, final String value) {
    final List<String> result = new ArrayList<>(values);
    result.add(value);
    return result;
  }

  private String toMarkdown(final TableBlock table) {
    final String header = "| " + String.join(" | ", table.headers()) + " |";
    final String separator =
        "| " + String.join(" | ", table.headers().stream().map(column -> "---").toList()) + " |";
    final List<String> rows = new ArrayList<>();
    for (final Map<String, String> row : table.rows()) {
      rows.add(
          "| "
              + String.join(
                  " | ", table.headers().stream().map(h -> row.getOrDefault(h, "")).toList())
              + " |");
    }
    return String.join(System.lineSeparator(), prepend(header, separator, rows));
  }

  private List<String> prepend(
      final String header, final String separator, final List<String> rows) {
    final List<String> lines = new ArrayList<>();
    lines.add(header);
    lines.add(separator);
    lines.addAll(rows);
    return lines;
  }

  private List<TextPart> splitBySentence(
      final String text,
      final SourceRange range,
      final String prefix,
      final ChunkingOptions options) {
    final BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
    iterator.setText(text);
    final List<TextPart> sentences = new ArrayList<>();
    int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
      final String rawSentence = text.substring(start, end);
      final String sentence = rawSentence.trim();
      if (sentence.isBlank()) {
        continue;
      }
      final int leadingWhitespace = rawSentence.indexOf(sentence);
      final long sentenceStart = range.start() + start + Math.max(leadingWhitespace, 0);
      final long sentenceEnd = sentenceStart + sentence.length();
      if (tokenCounter.count(withPrefix(prefix, sentence)) > options.maxTokens()) {
        throw new UnchunkableContentException("single sentence exceeds maxTokens");
      }
      sentences.add(new TextPart(sentence, new SourceRange(sentenceStart, sentenceEnd)));
    }

    final List<TextPart> parts = new ArrayList<>();
    int cursor = 0;
    while (cursor < sentences.size()) {
      final int first = cursor;
      final List<TextPart> current = new ArrayList<>();
      int end = cursor;
      while (end < sentences.size()) {
        final String candidate = joinSentences(current, sentences.get(end));
        if (!current.isEmpty()
            && tokenCounter.count(withPrefix(prefix, candidate)) > options.maxTokens()) {
          break;
        }
        current.add(sentences.get(end));
        end++;
        if (tokenCounter.count(withPrefix(prefix, candidate)) >= options.targetTokens()) {
          break;
        }
      }
      if (current.isEmpty()) {
        throw new UnchunkableContentException("unable to form sentence chunk");
      }
      final TextPart firstPart = current.get(0);
      final TextPart lastPart = current.get(current.size() - 1);
      parts.add(
          new TextPart(
              joinSentences(current, null),
              new SourceRange(firstPart.range().start(), lastPart.range().end())));

      int next = end;
      if (options.overlapTokens() > 0 && end < sentences.size()) {
        int overlapTokens = 0;
        int overlapStart = end;
        for (int index = end - 1; index >= first; index--) {
          final int sentenceTokens = tokenCounter.count(sentences.get(index).text());
          if (overlapTokens + sentenceTokens > options.overlapTokens()) {
            break;
          }
          overlapTokens += sentenceTokens;
          overlapStart = index;
        }
        if (overlapStart > first) {
          next = overlapStart;
        }
      }
      cursor = next > first ? next : end;
    }
    return parts;
  }

  private String withPrefix(final String prefix, final String text) {
    return prefix.isBlank() ? text : prefix + System.lineSeparator() + text;
  }

  private String joinSentences(final List<TextPart> parts, final TextPart additional) {
    final List<String> values = new ArrayList<>();
    parts.forEach(part -> values.add(part.text()));
    if (additional != null) {
      values.add(additional.text());
    }
    return String.join(" ", values);
  }

  private Chunk createChunk(
      final UUID documentVersionId,
      final int ordinal,
      final String sectionPath,
      final String text,
      final SourceRange range) {
    final Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("content_domain", ContentDomain.PUBLIC.name());
    metadata.put("section_path", sectionPath);
    metadata.put("source_char_start", Long.toString(range.start()));
    metadata.put("source_char_end", Long.toString(range.end()));
    return new Chunk(
        UUID.randomUUID(),
        documentVersionId,
        ordinal,
        sectionPath,
        text,
        tokenCounter.count(text),
        range,
        metadata);
  }

  private record TextPart(String text, SourceRange range) {}

  public static final class UnchunkableContentException extends RuntimeException {
    public UnchunkableContentException(final String message) {
      super(message);
    }
  }
}
