package com.medassist.agent.llm.routing;

import com.medassist.agent.llm.LlmCallMetadata;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable provider configuration. Model aliases that can silently move are rejected. */
public record LlmProviderDefinition(
    String id,
    URI endpoint,
    String apiKey,
    String model,
    String destination,
    Duration firstTokenTimeout,
    Duration overallTimeout,
    BigDecimal inputCostPer1kTokens,
    BigDecimal outputCostPer1kTokens,
    int requestsPerMinute,
    int maxOutputTokens) {
  private static final Pattern PINNED_MODEL =
      Pattern.compile(
          ".*(?:\\d{4}[-_.]?\\d{2}[-_.]?\\d{2}|@sha256:[a-f0-9]{8,}|-v\\d+(?:\\.\\d+)*|\\d{6,}).*");

  public LlmProviderDefinition {
    id = requireText(id, "id");
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    if (!"http".equalsIgnoreCase(endpoint.getScheme())
        && !"https".equalsIgnoreCase(endpoint.getScheme())) {
      throw new IllegalArgumentException("provider endpoint must use HTTP or HTTPS");
    }
    apiKey = apiKey == null ? "" : apiKey;
    model = requireText(model, "model");
    final String normalizedModel = model.toLowerCase(Locale.ROOT);
    if (normalizedModel.equals("latest")
        || normalizedModel.endsWith("-latest")
        || !PINNED_MODEL.matcher(normalizedModel).matches()) {
      throw new IllegalArgumentException("provider model must be fixed to an immutable version");
    }
    destination = requireText(destination, "destination").toUpperCase(Locale.ROOT);
    firstTokenTimeout = requirePositive(firstTokenTimeout, "firstTokenTimeout");
    overallTimeout = requirePositive(overallTimeout, "overallTimeout");
    if (firstTokenTimeout.compareTo(overallTimeout) > 0) {
      throw new IllegalArgumentException("first-token timeout cannot exceed overall timeout");
    }
    inputCostPer1kTokens = requireNonNegative(inputCostPer1kTokens, "input cost");
    outputCostPer1kTokens = requireNonNegative(outputCostPer1kTokens, "output cost");
    if (requestsPerMinute <= 0 || maxOutputTokens <= 0) {
      throw new IllegalArgumentException("provider limits must be positive");
    }
  }

  public LlmCallMetadata metadata() {
    return new LlmCallMetadata(id, model, overallTimeout);
  }

  public BigDecimal estimateCost(final int inputUtf8Bytes) {
    // Byte-level tokenizers cannot emit more tokens than UTF-8 input bytes.
    final long estimatedInputTokens = Math.max(1L, inputUtf8Bytes);
    return BigDecimal.valueOf(estimatedInputTokens)
        .multiply(inputCostPer1kTokens)
        .add(BigDecimal.valueOf(maxOutputTokens).multiply(outputCostPer1kTokens))
        .divide(BigDecimal.valueOf(1000));
  }

  private static String requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static Duration requirePositive(final Duration value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static BigDecimal requireNonNegative(final BigDecimal value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }
}
