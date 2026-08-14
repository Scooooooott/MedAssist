package com.medassist.common.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and creates W3C trace context without accepting arbitrary header text. */
public final class TraceParent {
  private static final Pattern FORMAT =
      Pattern.compile("^[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-[0-9a-fA-F]{2}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private TraceParent() {}

  public static Optional<String> traceId(final String value) {
    if (value == null) {
      return Optional.empty();
    }
    final Matcher matcher = FORMAT.matcher(value.trim());
    if (!matcher.matches() || allZero(matcher.group(1)) || allZero(matcher.group(2))) {
      return Optional.empty();
    }
    return Optional.of(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
  }

  public static String create() {
    final byte[] traceId = new byte[16];
    do {
      RANDOM.nextBytes(traceId);
    } while (allZero(traceId));
    return createForTraceId(HexFormat.of().formatHex(traceId));
  }

  public static String createForTraceId(final String traceId) {
    if (traceId == null || !traceId.matches("^[0-9a-fA-F]{32}$") || allZero(traceId)) {
      throw new IllegalArgumentException("traceId must be a non-zero 32-character hex value");
    }
    final byte[] spanId = new byte[8];
    do {
      RANDOM.nextBytes(spanId);
    } while (allZero(spanId));
    return "00-"
        + traceId.toLowerCase(java.util.Locale.ROOT)
        + "-"
        + HexFormat.of().formatHex(spanId)
        + "-01";
  }

  private static boolean allZero(final String value) {
    return value.chars().allMatch(character -> character == '0');
  }

  private static boolean allZero(final byte[] value) {
    for (final byte item : value) {
      if (item != 0) {
        return false;
      }
    }
    return true;
  }
}
