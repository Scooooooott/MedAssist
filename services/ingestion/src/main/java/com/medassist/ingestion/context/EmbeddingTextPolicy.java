package com.medassist.ingestion.context;

import java.util.Objects;

/** Explicit boundary between embedding text and all original-text consumers. */
public final class EmbeddingTextPolicy {
  private EmbeddingTextPolicy() {}

  public static String embeddingText(final String contextPrefix, final String originalText) {
    Objects.requireNonNull(contextPrefix, "contextPrefix");
    Objects.requireNonNull(originalText, "originalText");
    if (contextPrefix.isBlank()) {
      return originalText;
    }
    return contextPrefix + "\n\n" + originalText;
  }

  public static String lexicalText(final String originalText) {
    return requireOriginalText(originalText);
  }

  public static String rerankText(final String originalText) {
    return requireOriginalText(originalText);
  }

  public static String finalContextText(final String originalText) {
    return requireOriginalText(originalText);
  }

  public static String generationText(final String originalText) {
    return requireOriginalText(originalText);
  }

  public static String citationText(final String originalText) {
    return requireOriginalText(originalText);
  }

  public static String displayText(final String originalText) {
    return requireOriginalText(originalText);
  }

  private static String requireOriginalText(final String originalText) {
    return Objects.requireNonNull(originalText, "originalText");
  }
}
