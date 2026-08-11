package com.medassist.ingestion.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Computes SHA-256 without buffering an entire object in memory. */
public final class Sha256Hasher {
  private static final int BUFFER_SIZE = 8192;

  public String hash(final ObjectDescriptor object) throws DiscoveryTransientException {
    final MessageDigest digest = newDigest();
    final byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream input = object.openStream()) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      return toHex(digest.digest());
    } catch (final IOException exception) {
      throw new DiscoveryTransientException(
          "Unable to read object for SHA-256: " + object.storageUri(), exception);
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the Java runtime", exception);
    }
  }

  private static String toHex(final byte[] bytes) {
    final StringBuilder result = new StringBuilder(bytes.length * 2);
    for (final byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      result.append(Character.forDigit(value & 0x0f, 16));
    }
    return result.toString();
  }
}
