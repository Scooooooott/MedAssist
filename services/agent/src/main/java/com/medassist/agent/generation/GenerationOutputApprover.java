package com.medassist.agent.generation;

import com.medassist.agent.security.SensitiveContentScanner;
import java.util.ArrayList;
import java.util.List;

/** Fail-closed approval boundary applied before any answer text reaches Redis or a browser. */
public final class GenerationOutputApprover {
  private final int maxChunkCharacters;

  public GenerationOutputApprover(final int maxChunkCharacters) {
    if (maxChunkCharacters <= 0) {
      throw new IllegalArgumentException("maxChunkCharacters must be positive");
    }
    this.maxChunkCharacters = maxChunkCharacters;
  }

  public List<String> approveAndChunk(final String answer, final String rawQuery) {
    if (answer == null || answer.isBlank()) {
      return List.of();
    }
    if (containsUnsafeControl(answer)
        || containsRawQuery(answer, rawQuery)
        || !SensitiveContentScanner.find(answer).isEmpty()) {
      throw new OutputApprovalException();
    }
    final List<String> chunks = new ArrayList<>();
    int offset = 0;
    while (offset < answer.length()) {
      final int remainingCodePoints = answer.codePointCount(offset, answer.length());
      final int end =
          answer.offsetByCodePoints(offset, Math.min(maxChunkCharacters, remainingCodePoints));
      final String chunk = answer.substring(offset, end);
      if (containsUnsafeControl(chunk)) {
        throw new OutputApprovalException();
      }
      chunks.add(chunk);
      offset = end;
    }
    return List.copyOf(chunks);
  }

  private static boolean containsRawQuery(final String answer, final String rawQuery) {
    return rawQuery != null && !rawQuery.isBlank() && answer.contains(rawQuery);
  }

  private static boolean containsUnsafeControl(final String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint -> Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint));
  }

  static final class OutputApprovalException extends RuntimeException {
    OutputApprovalException() {
      super("client output did not pass approval");
    }
  }
}
