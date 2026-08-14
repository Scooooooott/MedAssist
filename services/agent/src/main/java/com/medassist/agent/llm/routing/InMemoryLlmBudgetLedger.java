package com.medassist.agent.llm.routing;

import com.medassist.agent.llm.LlmGatewayException;
import com.medassist.common.context.ExecutionContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Race-safe ledger for a single agent replica; the interface permits a distributed store later. */
public final class InMemoryLlmBudgetLedger implements LlmBudgetLedger {
  private final LlmBudgetLimits limits;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, BigDecimal> totals = new HashMap<>();
  private final Map<String, Reservation> reservations = new HashMap<>();

  public InMemoryLlmBudgetLedger(final LlmBudgetLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public Reservation reserve(
      final ExecutionContext context, final BigDecimal estimatedCost, final Instant now) {
    Objects.requireNonNull(context, "context");
    requireNonNegative(estimatedCost);
    final String role = effectiveRole(context);
    lock.lock();
    try {
      final BudgetWindow window = window(context.subject(), role, now);
      if (exceeds(window.dailyGlobalSpent(), estimatedCost, limits.daily())
          || exceeds(window.monthlyGlobalSpent(), estimatedCost, limits.monthly())
          || exceeds(window.dailyRoleSpent(), estimatedCost, window.dailyRoleLimit())
          || exceeds(window.monthlyRoleSpent(), estimatedCost, window.monthlyRoleLimit())
          || exceeds(window.dailyUserSpent(), estimatedCost, window.dailyUserLimit())
          || exceeds(window.monthlyUserSpent(), estimatedCost, window.monthlyUserLimit())) {
        throw LlmGatewayException.budgetExceeded();
      }
      add(window.dailyGlobalKey(), estimatedCost);
      add(window.monthlyGlobalKey(), estimatedCost);
      add(window.dailyRoleKey(), estimatedCost);
      add(window.monthlyRoleKey(), estimatedCost);
      add(window.dailyUserKey(), estimatedCost);
      add(window.monthlyUserKey(), estimatedCost);
      final Reservation reservation =
          new Reservation(
              UUID.randomUUID().toString(),
              context.subject(),
              role,
              estimatedCost,
              java.util.List.of(
                  window.dailyGlobalKey(),
                  window.monthlyGlobalKey(),
                  window.dailyRoleKey(),
                  window.monthlyRoleKey(),
                  window.dailyUserKey(),
                  window.monthlyUserKey()));
      reservations.put(reservation.id(), reservation);
      return reservation;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void commit(
      final Reservation reservation, final BigDecimal actualCost, final Instant now) {
    Objects.requireNonNull(reservation, "reservation");
    requireNonNegative(actualCost);
    lock.lock();
    try {
      final Reservation active = reservations.remove(reservation.id());
      if (active == null) {
        return;
      }
      final BigDecimal adjustment = actualCost.subtract(active.amount());
      active.accountingKeys().forEach(key -> add(key, adjustment));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void release(final Reservation reservation) {
    Objects.requireNonNull(reservation, "reservation");
    lock.lock();
    try {
      final Reservation active = reservations.remove(reservation.id());
      if (active == null) {
        return;
      }
      active
          .accountingKeys()
          .forEach(
              key ->
                  totals.computeIfPresent(
                      key,
                      (ignored, value) -> value.subtract(active.amount()).max(BigDecimal.ZERO)));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public BudgetSnapshot snapshot(final ExecutionContext context, final Instant now) {
    lock.lock();
    try {
      final String role = effectiveRole(context);
      final BudgetWindow window = window(context.subject(), role, now);
      final Usage daily =
          mostConsumed(
              new Usage(window.dailyGlobalSpent(), limits.daily()),
              new Usage(window.dailyRoleSpent(), window.dailyRoleLimit()),
              new Usage(window.dailyUserSpent(), window.dailyUserLimit()));
      final Usage monthly =
          mostConsumed(
              new Usage(window.monthlyGlobalSpent(), limits.monthly()),
              new Usage(window.monthlyRoleSpent(), window.monthlyRoleLimit()),
              new Usage(window.monthlyUserSpent(), window.monthlyUserLimit()));
      return new BudgetSnapshot(
          daily.spent(),
          daily.limit(),
          monthly.spent(),
          monthly.limit(),
          ratio(daily.spent(), daily.limit()).compareTo(limits.softThreshold()) >= 0
              || ratio(monthly.spent(), monthly.limit()).compareTo(limits.softThreshold()) >= 0);
    } finally {
      lock.unlock();
    }
  }

  private BudgetWindow window(final String subject, final String role, final Instant now) {
    final String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    final String month = YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC)).toString();
    final String dg = "d:" + day + ":global";
    final String mg = "m:" + month + ":global";
    final String dr = "d:" + day + ":role:" + role;
    final String mr = "m:" + month + ":role:" + role;
    final String du = "d:" + day + ":user:" + subject;
    final String mu = "m:" + month + ":user:" + subject;
    return new BudgetWindow(
        dg,
        mg,
        dr,
        mr,
        du,
        mu,
        total(dg),
        total(mg),
        total(dr),
        total(mr),
        total(du),
        total(mu),
        limits.dailyByRole().getOrDefault(role, limits.daily()),
        limits.monthlyByRole().getOrDefault(role, limits.monthly()),
        limits.dailyByUser().getOrDefault(subject, limits.daily()),
        limits.monthlyByUser().getOrDefault(subject, limits.monthly()));
  }

  private static String effectiveRole(final ExecutionContext context) {
    if (context.roles().size() != 1) {
      throw LlmGatewayException.budgetExceeded();
    }
    return context.roles().iterator().next();
  }

  private void add(final String key, final BigDecimal amount) {
    totals.merge(key, amount, BigDecimal::add);
  }

  private BigDecimal total(final String key) {
    return totals.getOrDefault(key, BigDecimal.ZERO);
  }

  private static boolean exceeds(
      final BigDecimal spent, final BigDecimal reservation, final BigDecimal limit) {
    return spent.add(reservation).compareTo(limit) > 0;
  }

  private static Usage mostConsumed(final Usage... candidates) {
    Usage result = candidates[0];
    for (final Usage candidate : candidates) {
      if (ratio(candidate.spent(), candidate.limit())
              .compareTo(ratio(result.spent(), result.limit()))
          > 0) {
        result = candidate;
      }
    }
    return result;
  }

  private static BigDecimal ratio(final BigDecimal value, final BigDecimal limit) {
    return value.divide(limit, 4, java.math.RoundingMode.HALF_UP);
  }

  private static void requireNonNegative(final BigDecimal value) {
    Objects.requireNonNull(value, "cost");
    if (value.signum() < 0) {
      throw new IllegalArgumentException("cost must be non-negative");
    }
  }

  private record BudgetWindow(
      String dailyGlobalKey,
      String monthlyGlobalKey,
      String dailyRoleKey,
      String monthlyRoleKey,
      String dailyUserKey,
      String monthlyUserKey,
      BigDecimal dailyGlobalSpent,
      BigDecimal monthlyGlobalSpent,
      BigDecimal dailyRoleSpent,
      BigDecimal monthlyRoleSpent,
      BigDecimal dailyUserSpent,
      BigDecimal monthlyUserSpent,
      BigDecimal dailyRoleLimit,
      BigDecimal monthlyRoleLimit,
      BigDecimal dailyUserLimit,
      BigDecimal monthlyUserLimit) {}

  private record Usage(BigDecimal spent, BigDecimal limit) {}
}
