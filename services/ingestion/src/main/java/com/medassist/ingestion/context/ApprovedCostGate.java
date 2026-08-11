package com.medassist.ingestion.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Validates the reviewed estimate artifact before allowing an LLM call. */
public final class ApprovedCostGate {
  private final Path estimateArtifact;
  private final String configuredSha256;
  private final ObjectMapper objectMapper;

  public ApprovedCostGate(final Path estimateArtifact, final String configuredSha256) {
    this(estimateArtifact, configuredSha256, new ObjectMapper());
  }

  ApprovedCostGate(
      final Path estimateArtifact, final String configuredSha256, final ObjectMapper objectMapper) {
    this.estimateArtifact = Objects.requireNonNull(estimateArtifact, "estimateArtifact");
    this.configuredSha256 = normalizeHash(configuredSha256);
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public void requireApproved() {
    final byte[] artifactBytes;
    try {
      artifactBytes = Files.readAllBytes(estimateArtifact);
    } catch (final IOException exception) {
      throw new ContextCostGateException("approved cost artifact is missing or unreadable");
    }
    if (!sha256(artifactBytes).equals(configuredSha256)) {
      throw new ContextCostGateException("approved cost artifact SHA-256 does not match");
    }
    final JsonNode root;
    try {
      root = objectMapper.readTree(new String(artifactBytes, StandardCharsets.UTF_8));
    } catch (final IOException | RuntimeException exception) {
      throw new ContextCostGateException("approved cost artifact is not valid JSON");
    }
    if (root == null || !root.isObject() || !root.path("within_budget").isBoolean()) {
      throw new ContextCostGateException(
          "approved cost artifact must contain boolean within_budget");
    }
    if (!root.path("within_budget").booleanValue()) {
      throw new ContextCostGateException("approved cost estimate is over budget");
    }
  }

  private static String normalizeHash(final String value) {
    Objects.requireNonNull(value, "configuredSha256");
    final String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "configuredSha256 must be a 64-character hexadecimal hash");
    }
    return normalized;
  }

  private static String sha256(final byte[] bytes) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
