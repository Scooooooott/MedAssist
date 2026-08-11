package com.medassist.ingestion.pipeline.mapping;

import com.medassist.domain.SourceRange;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Numeric-only mapping from de-identified text offsets back to original document ranges. */
public final class SourceRangeMap {
  private static final String VERSION = "v1";
  private static final String METADATA_PREFIX = "__medassist.source-range.";

  private final int transformedLength;
  private final List<Segment> segments;

  public SourceRangeMap(final int transformedLength, final List<Segment> segments) {
    if (transformedLength < 0) {
      throw new IllegalArgumentException("transformedLength must be non-negative");
    }
    this.transformedLength = transformedLength;
    this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    validateSegments();
  }

  public static String metadataKey(final String fieldId) {
    Objects.requireNonNull(fieldId, "fieldId");
    final String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(fieldId.getBytes(StandardCharsets.UTF_8));
    return METADATA_PREFIX + encoded;
  }

  public static String sectionTextField(final String path) {
    return "section-text:" + Objects.requireNonNull(path, "path");
  }

  public static String tableTextField(final String path) {
    return "table-text:" + Objects.requireNonNull(path, "path");
  }

  public static String tableTextField(
      final String path, final long sourceStart, final long sourceEnd) {
    return tableTextField(path) + ":" + sourceStart + ":" + sourceEnd;
  }

  public static SourceRangeMap parse(final String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("source range map is missing");
    }
    final String[] parts = encoded.split("\\|", -1);
    if (parts.length != 3 || !VERSION.equals(parts[0]) || !parts[2].startsWith("s=")) {
      throw new IllegalArgumentException("invalid source range map version or shape");
    }
    final int transformedLength = parseNonNegative(parts[1], "transformed length");
    final String segmentText = parts[2].substring(2);
    final List<Segment> segments = new ArrayList<>();
    if (!segmentText.isBlank()) {
      for (final String value : segmentText.split(",", -1)) {
        final String[] numbers = value.split("-", -1);
        if (numbers.length != 4) {
          throw new IllegalArgumentException("invalid source range segment");
        }
        segments.add(
            new Segment(
                parseNonNegative(numbers[0], "transformed start"),
                parseNonNegative(numbers[1], "transformed end"),
                parseNonNegative(numbers[2], "source start"),
                parseNonNegative(numbers[3], "source end")));
      }
    }
    return new SourceRangeMap(transformedLength, segments);
  }

  public SourceRange rangeFor(final int transformedStart, final int transformedEnd) {
    if (transformedStart < 0
        || transformedEnd < transformedStart
        || transformedEnd > transformedLength) {
      throw new IllegalArgumentException("requested range is outside mapped text");
    }
    if (transformedStart == transformedEnd) {
      throw new IllegalArgumentException("empty mapped range is not supported");
    }
    Segment first = null;
    Segment last = null;
    for (final Segment segment : segments) {
      if (segment.transformedEnd() <= transformedStart) {
        continue;
      }
      if (segment.transformedStart() >= transformedEnd) {
        break;
      }
      if (first == null) {
        first = segment;
      }
      last = segment;
    }
    if (first == null || last == null) {
      throw new IllegalArgumentException("requested range is not covered by mapping");
    }
    return new SourceRange(first.sourceStart(), last.sourceEnd());
  }

  public String serialize() {
    final StringBuilder result = new StringBuilder(VERSION).append('|').append(transformedLength);
    result.append("|s=");
    for (int index = 0; index < segments.size(); index++) {
      if (index > 0) {
        result.append(',');
      }
      final Segment segment = segments.get(index);
      result
          .append(segment.transformedStart())
          .append('-')
          .append(segment.transformedEnd())
          .append('-')
          .append(segment.sourceStart())
          .append('-')
          .append(segment.sourceEnd());
    }
    return result.toString();
  }

  private static int parseNonNegative(final String value, final String name) {
    try {
      final int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw new IllegalArgumentException(name + " must be non-negative");
      }
      return parsed;
    } catch (final NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  private void validateSegments() {
    int cursor = 0;
    for (final Segment segment : segments) {
      if (segment.transformedStart() != cursor
          || segment.transformedEnd() <= segment.transformedStart()
          || segment.sourceEnd() <= segment.sourceStart()
          || segment.transformedEnd() > transformedLength) {
        throw new IllegalArgumentException("source range map contains a gap or invalid segment");
      }
      cursor = segment.transformedEnd();
    }
    if (cursor != transformedLength && transformedLength != 0) {
      throw new IllegalArgumentException("source range map does not cover transformed text");
    }
  }

  public record Segment(
      int transformedStart, int transformedEnd, long sourceStart, long sourceEnd) {
    public Segment {
      if (transformedStart < 0
          || transformedEnd <= transformedStart
          || sourceStart < 0
          || sourceEnd <= sourceStart) {
        throw new IllegalArgumentException("invalid source range map segment");
      }
    }
  }
}
