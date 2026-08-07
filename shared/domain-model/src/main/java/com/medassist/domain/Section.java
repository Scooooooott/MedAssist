package com.medassist.domain;

import java.util.List;
import java.util.Objects;

public record Section(
    String path,
    String heading,
    int level,
    String text,
    List<Section> children,
    SourceRange sourceRange) {
  public Section {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(heading, "heading");
    Objects.requireNonNull(text, "text");
    children = List.copyOf(children);
    if (level < 0) {
      throw new IllegalArgumentException("level must be non-negative");
    }
  }

  public Section(
      final String path,
      final String heading,
      final int level,
      final String text,
      final List<Section> children) {
    this(path, heading, level, text, children, new SourceRange(0, text.length()));
  }
}
