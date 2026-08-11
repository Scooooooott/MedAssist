package com.medassist.agent.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** A value that has crossed the ingress deidentification boundary. */
public record DeidentifiedQuery(String value, String originalQueryHash) {
  public DeidentifiedQuery {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(originalQueryHash, "originalQueryHash");
    if (value.isBlank()) {
      throw new IllegalArgumentException("deidentified query must not be blank");
    }
    if (originalQueryHash.isBlank()) {
      throw new IllegalArgumentException("original query hash must not be blank");
    }
  }

  /** Compatibility constructor for test doubles that only return deidentified text. */
  public DeidentifiedQuery(final String value) {
    this(value, sha256(value));
  }

  private static String sha256(final String value) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(digest.length * 2);
      for (final byte valueByte : digest) {
        hex.append(String.format("%02x", valueByte));
      }
      return "sha256:" + hex;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
