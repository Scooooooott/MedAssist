package com.medassist.agent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashing {
  private Hashing() {}

  static String sha256(final String value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] bytes =
          digest.digest((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (final byte valueByte : bytes) {
        hex.append(String.format("%02x", valueByte));
      }
      return hex.toString();
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
