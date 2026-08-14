package com.medassist.agent.llm.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.agent.llm.LlmFailureReason;
import com.medassist.agent.llm.LlmGatewayException;
import com.medassist.common.context.ExecutionContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryLlmBudgetLedgerTest {
  private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

  @Test
  void anotherUsersSpendDoesNotConsumeTheCurrentUsersQuota() {
    final InMemoryLlmBudgetLedger ledger =
        new InMemoryLlmBudgetLedger(
            limits(
                "10.00",
                "100.00",
                Map.of("user-a", new BigDecimal("1.00"), "user-b", new BigDecimal("1.00"))));

    final var first = ledger.reserve(context("user-a"), new BigDecimal("0.90"), NOW);
    ledger.commit(first, new BigDecimal("0.90"), NOW);

    assertThatCode(() -> ledger.reserve(context("user-b"), new BigDecimal("0.90"), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void globalRoleAndUserLimitsAreEachEnforced() {
    final InMemoryLlmBudgetLedger userLedger =
        new InMemoryLlmBudgetLedger(limits("10.00", "100.00", Map.of("user-a", BigDecimal.ONE)));
    final var reservation = userLedger.reserve(context("user-a"), new BigDecimal("0.75"), NOW);
    userLedger.commit(reservation, new BigDecimal("0.75"), NOW);

    assertBudgetExceeded(() -> userLedger.reserve(context("user-a"), new BigDecimal("0.30"), NOW));

    final LlmBudgetLimits roleLimits =
        new LlmBudgetLimits(
            BigDecimal.TEN,
            BigDecimal.valueOf(100),
            new BigDecimal("0.80"),
            Map.of("RESEARCHER", BigDecimal.ONE),
            Map.of(),
            Map.of(),
            Map.of());
    final InMemoryLlmBudgetLedger roleLedger = new InMemoryLlmBudgetLedger(roleLimits);
    roleLedger.reserve(context("user-a"), new BigDecimal("0.75"), NOW);
    assertBudgetExceeded(() -> roleLedger.reserve(context("user-b"), new BigDecimal("0.30"), NOW));

    final InMemoryLlmBudgetLedger globalLedger =
        new InMemoryLlmBudgetLedger(limits("1.00", "100.00", Map.of()));
    globalLedger.reserve(context("user-a"), new BigDecimal("0.75"), NOW);
    assertBudgetExceeded(
        () -> globalLedger.reserve(context("user-b"), new BigDecimal("0.30"), NOW));
  }

  @Test
  void releasedReservationRestoresEveryAccountingWindow() {
    final InMemoryLlmBudgetLedger ledger =
        new InMemoryLlmBudgetLedger(limits("1.00", "100.00", Map.of("user-a", BigDecimal.ONE)));
    final var reservation = ledger.reserve(context("user-a"), BigDecimal.ONE, NOW);

    ledger.release(reservation);

    assertThatCode(() -> ledger.reserve(context("user-a"), BigDecimal.ONE, NOW))
        .doesNotThrowAnyException();
    assertThat(ledger.snapshot(context("user-a"), NOW).dailySpent()).isEqualByComparingTo("1.00");
  }

  private static LlmBudgetLimits limits(
      final String daily, final String monthly, final Map<String, BigDecimal> dailyByUser) {
    return new LlmBudgetLimits(
        new BigDecimal(daily),
        new BigDecimal(monthly),
        new BigDecimal("0.80"),
        Map.of(),
        Map.of(),
        dailyByUser,
        Map.of());
  }

  private static ExecutionContext context(final String subject) {
    return new ExecutionContext(subject, Set.of("RESEARCHER"), "request-1", "trace-1", Map.of());
  }

  private static void assertBudgetExceeded(
      final org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(LlmGatewayException.class)
        .extracting(exception -> ((LlmGatewayException) exception).reason())
        .isEqualTo(LlmFailureReason.BUDGET_EXCEEDED);
  }
}
