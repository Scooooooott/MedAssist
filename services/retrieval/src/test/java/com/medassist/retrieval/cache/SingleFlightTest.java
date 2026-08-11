package com.medassist.retrieval.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {
  @Test
  void twentyConcurrentCallersExecuteSupplierOnce() throws Exception {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final SingleFlight singleFlight = new SingleFlight(new CacheMetrics(registry));
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch ready = new CountDownLatch(20);
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(20);
    try {
      final List<Future<String>> futures = new ArrayList<>();
      for (int index = 0; index < 20; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await(2, TimeUnit.SECONDS);
                  return singleFlight.execute(
                      "same-key",
                      () -> {
                        calls.incrementAndGet();
                        try {
                          Thread.sleep(50);
                        } catch (final InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        return "value";
                      });
                }));
      }
      ready.await(2, TimeUnit.SECONDS);
      start.countDown();
      for (final Future<String> future : futures) {
        assertEquals("value", future.get(2, TimeUnit.SECONDS));
      }
      assertEquals(1, calls.get());
      assertEquals(
          19.0,
          registry
              .find("medassist.cache.single_flight.join")
              .tag("cache", "other")
              .counter()
              .count());
    } finally {
      executor.shutdownNow();
      registry.close();
    }
  }

  @Test
  void recordsJoinWithTheCacheScope() throws Exception {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final SingleFlight singleFlight = new SingleFlight(new CacheMetrics(registry));
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch joinerReady = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    final Counter joinCounter =
        registry.counter("medassist.cache.single_flight.join", "cache", "embedding");
    try {
      final Future<String> owner =
          executor.submit(
              () ->
                  singleFlight.execute(
                      "embedding",
                      "same-key",
                      () -> {
                        entered.countDown();
                        try {
                          release.await(2, TimeUnit.SECONDS);
                        } catch (final InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        return "value";
                      }));
      entered.await(2, TimeUnit.SECONDS);
      final Future<String> joiner =
          executor.submit(
              () -> {
                joinerReady.countDown();
                return singleFlight.execute("embedding", "same-key", () -> "unexpected");
              });
      joinerReady.await(2, TimeUnit.SECONDS);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (joinCounter.count() < 1.0 && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertTrue(joinCounter.count() >= 1.0);
      release.countDown();
      assertEquals("value", owner.get(2, TimeUnit.SECONDS));
      assertEquals("value", joiner.get(2, TimeUnit.SECONDS));
      assertEquals(
          1.0,
          registry
              .find("medassist.cache.single_flight.join")
              .tag("cache", "embedding")
              .counter()
              .count());
    } finally {
      release.countDown();
      executor.shutdownNow();
      registry.close();
    }
  }

  @Test
  void joinObserverReceivesTheOwnersMeasuredValueOnce() throws Exception {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final SingleFlight singleFlight = new SingleFlight(new CacheMetrics(registry));
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger observed = new AtomicInteger();
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    final Counter joinCounter =
        registry.counter("medassist.cache.single_flight.join", "cache", "answer");
    try {
      final Future<Integer> owner =
          executor.submit(
              () ->
                  singleFlight.execute(
                      "answer",
                      "same-key",
                      () -> {
                        entered.countDown();
                        try {
                          release.await(2, TimeUnit.SECONDS);
                        } catch (final InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        return 17;
                      },
                      ignored -> observed.incrementAndGet()));
      entered.await(2, TimeUnit.SECONDS);
      final Future<Integer> joiner =
          executor.submit(
              () ->
                  singleFlight.execute(
                      "answer", "same-key", () -> 99, value -> observed.addAndGet(value)));
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (joinCounter.count() < 1.0 && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertEquals(1.0, joinCounter.count());
      release.countDown();

      assertEquals(17, owner.get(2, TimeUnit.SECONDS));
      assertEquals(17, joiner.get(2, TimeUnit.SECONDS));
      assertEquals(17, observed.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
      registry.close();
    }
  }
}
