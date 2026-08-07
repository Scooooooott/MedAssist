package com.medassist.domain;

public record SourceRange(long start, long end) {
  public SourceRange {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("invalid source range");
    }
  }

  public boolean overlaps(final SourceRange other) {
    return start < other.end() && other.start() < end;
  }
}
