package com.medassist.agent.llm;

import java.math.BigDecimal;
import java.util.Objects;

/** Cost accounting result. Unknown costs are represented by known=false and zero values. */
public record LlmCost(
    boolean known,
    BigDecimal inputCost,
    BigDecimal outputCost,
    BigDecimal totalCost,
    String currency,
    LlmCallMetadata metadata) {
  public LlmCost {
    inputCost = Objects.requireNonNull(inputCost, "input cost");
    outputCost = Objects.requireNonNull(outputCost, "output cost");
    totalCost = Objects.requireNonNull(totalCost, "total cost");
    currency = Objects.requireNonNull(currency, "currency");
    metadata = Objects.requireNonNull(metadata, "metadata");
    if (inputCost.signum() < 0 || outputCost.signum() < 0 || totalCost.signum() < 0) {
      throw new IllegalArgumentException("costs must be non-negative");
    }
    if (currency.isBlank()) {
      throw new IllegalArgumentException("currency is required");
    }
  }

  public LlmCost(
      final boolean known,
      final BigDecimal inputCost,
      final BigDecimal outputCost,
      final BigDecimal totalCost,
      final String currency) {
    this(known, inputCost, outputCost, totalCost, currency, LlmCallMetadata.unconfigured());
  }

  public static LlmCost unknown() {
    return unknown(LlmCallMetadata.unconfigured());
  }

  public static LlmCost unknown(final LlmCallMetadata metadata) {
    return new LlmCost(
        false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "UNKNOWN", metadata);
  }
}
