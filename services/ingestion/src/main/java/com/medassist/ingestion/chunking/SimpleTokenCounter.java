package com.medassist.ingestion.chunking;

import java.util.regex.Pattern;

public final class SimpleTokenCounter implements TokenCounter {
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]");

  @Override
  public int count(final String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    return (int) TOKEN.matcher(text).results().count();
  }
}
