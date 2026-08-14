package com.medassist.agent.llm.routing;

import com.medassist.common.context.ExecutionContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface LlmBudgetLedger {
  Reservation reserve(ExecutionContext context, BigDecimal estimatedCost, Instant now);

  void commit(Reservation reservation, BigDecimal actualCost, Instant now);

  void release(Reservation reservation);

  BudgetSnapshot snapshot(ExecutionContext context, Instant now);

  record Reservation(
      String id, String subject, String role, BigDecimal amount, List<String> accountingKeys) {
    public Reservation {
      accountingKeys = List.copyOf(accountingKeys);
    }
  }

  record BudgetSnapshot(
      BigDecimal dailySpent,
      BigDecimal dailyLimit,
      BigDecimal monthlySpent,
      BigDecimal monthlyLimit,
      boolean softThresholdReached) {}
}
