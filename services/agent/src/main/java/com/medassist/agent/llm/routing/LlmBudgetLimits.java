package com.medassist.agent.llm.routing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public record LlmBudgetLimits(
    BigDecimal daily,
    BigDecimal monthly,
    BigDecimal softThreshold,
    Map<String, BigDecimal> dailyByRole,
    Map<String, BigDecimal> monthlyByRole,
    Map<String, BigDecimal> dailyByUser,
    Map<String, BigDecimal> monthlyByUser) {
  public LlmBudgetLimits {
    daily = positive(daily, "daily");
    monthly = positive(monthly, "monthly");
    softThreshold = Objects.requireNonNull(softThreshold, "softThreshold");
    if (softThreshold.signum() <= 0 || softThreshold.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("softThreshold must be in (0, 1]");
    }
    dailyByRole = immutablePositive(dailyByRole);
    monthlyByRole = immutablePositive(monthlyByRole);
    dailyByUser = immutablePositive(dailyByUser);
    monthlyByUser = immutablePositive(monthlyByUser);
  }

  public static LlmBudgetLimits defaults() {
    return new LlmBudgetLimits(
        BigDecimal.valueOf(25),
        BigDecimal.valueOf(500),
        new BigDecimal("0.80"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }

  private static Map<String, BigDecimal> immutablePositive(final Map<String, BigDecimal> values) {
    Objects.requireNonNull(values, "budget overrides");
    values.forEach(
        (key, value) -> {
          if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("budget override key is required");
          }
          positive(value, "budget override");
        });
    return Map.copyOf(values);
  }

  private static BigDecimal positive(final BigDecimal value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
