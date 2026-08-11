package com.medassist.auditgovernance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Stable, dependency-free canonical representation used as the hash-chain input. */
public final class CanonicalAuditEventSerializer {
  private CanonicalAuditEventSerializer() {}

  public static String serialize(final AuditEvent event) {
    final StringBuilder builder = new StringBuilder(512);
    builder.append('{');
    field(builder, "eventId", event.eventId().toString());
    field(builder, "timestamp", event.timestamp().toString());
    field(builder, "actor", event.actor());
    field(builder, "category", event.category().name());
    field(builder, "role", event.role());
    field(builder, "action", event.action());
    field(builder, "resourceType", event.resourceType());
    field(builder, "resourceId", event.resourceId());
    field(builder, "outcome", event.outcome());
    builder.append("\"payload\":{");
    boolean first = true;
    for (final Map.Entry<String, String> entry :
        event.payload().fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      appendString(builder, entry.getKey());
      builder.append(':');
      appendString(builder, entry.getValue());
    }
    builder.append('}');
    field(builder, "payloadHash", event.payloadHash());
    field(builder, "previousHash", event.previousHash());
    if (builder.charAt(builder.length() - 1) == ',') {
      builder.setLength(builder.length() - 1);
    }
    builder.append('}');
    return builder.toString();
  }

  public static String hash(final AuditEvent event) {
    return sha256(serialize(event));
  }

  public static String hashPayload(final AuditPayload payload) {
    final StringBuilder builder = new StringBuilder("{");
    boolean first = true;
    for (final Map.Entry<String, String> entry :
        payload.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      appendString(builder, entry.getKey());
      builder.append(':');
      appendString(builder, entry.getValue());
    }
    builder.append('}');
    return sha256(builder.toString());
  }

  private static void field(final StringBuilder builder, final String name, final String value) {
    if (builder.charAt(builder.length() - 1) != '{') {
      builder.append(',');
    }
    appendString(builder, name);
    builder.append(':');
    appendString(builder, value);
  }

  private static void appendString(final StringBuilder builder, final String value) {
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (character < 0x20) {
            builder.append(String.format("\\u%04x", (int) character));
          } else {
            builder.append(character);
          }
        }
      }
    }
    builder.append('"');
  }

  private static String sha256(final String value) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(digest.length * 2);
      for (final byte element : digest) {
        hex.append(String.format("%02x", element & 0xff));
      }
      return hex.toString();
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }
}
