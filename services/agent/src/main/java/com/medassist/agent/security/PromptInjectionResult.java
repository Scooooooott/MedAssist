package com.medassist.agent.security;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record PromptInjectionResult(
    boolean detected, Set<PromptInjectionCategory> classifications) {
  public PromptInjectionResult {
    Objects.requireNonNull(classifications, "classifications");
    final EnumSet<PromptInjectionCategory> copy =
        classifications.isEmpty()
            ? EnumSet.noneOf(PromptInjectionCategory.class)
            : EnumSet.copyOf(classifications);
    if (detected) {
      copy.remove(PromptInjectionCategory.NONE);
      if (copy.isEmpty()) {
        throw new IllegalArgumentException("a detected result must have a classification");
      }
    } else {
      copy.clear();
      copy.add(PromptInjectionCategory.NONE);
    }
    classifications = Set.copyOf(copy);
  }

  public PromptInjectionCategory classification() {
    return classifications.stream()
        .min(Comparator.comparingInt(PromptInjectionCategory::ordinal))
        .orElse(PromptInjectionCategory.NONE);
  }

  public boolean hasClassification(final PromptInjectionCategory category) {
    return classifications.contains(category);
  }
}
