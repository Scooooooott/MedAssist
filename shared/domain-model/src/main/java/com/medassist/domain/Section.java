package com.medassist.domain;

import java.util.List;
import java.util.Objects;

public record Section(String path, String heading, int level, String text, List<Section> children) {
  public Section {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(heading, "heading");
    Objects.requireNonNull(text, "text");
    children = List.copyOf(children);
    if (level < 0) {
      throw new IllegalArgumentException("level must be non-negative");
    }
  }
}
