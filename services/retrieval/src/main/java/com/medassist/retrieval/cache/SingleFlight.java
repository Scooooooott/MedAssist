package com.medassist.retrieval.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class SingleFlight {
  private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight =
      new ConcurrentHashMap<>();
  private final CacheMetrics metrics;

  public SingleFlight() {
    this(CacheMetrics.defaultInstance());
  }

  @Autowired
  public SingleFlight(final CacheMetrics metrics) {
    this.metrics = metrics;
  }

  public <T> T execute(final String key, final Supplier<T> supplier) {
    return execute("other", key, supplier);
  }

  public <T> T execute(final String cache, final String key, final Supplier<T> supplier) {
    return execute(cache, key, supplier, ignored -> {});
  }

  public <T> T execute(
      final String cache,
      final String key,
      final Supplier<T> supplier,
      final Consumer<T> joinObserver) {
    final CompletableFuture<Object> created = new CompletableFuture<>();
    final CompletableFuture<Object> existing = inFlight.putIfAbsent(key, created);
    if (existing != null) {
      metrics.singleFlightJoin(cache);
      try {
        @SuppressWarnings("unchecked")
        final T value = (T) existing.join();
        joinObserver.accept(value);
        return value;
      } catch (final CompletionException exception) {
        throw unwrap(exception);
      }
    }
    try {
      final T value = supplier.get();
      created.complete(value);
      return value;
    } catch (final RuntimeException | Error exception) {
      created.completeExceptionally(exception);
      throw exception;
    } finally {
      inFlight.remove(key, created);
    }
  }

  private RuntimeException unwrap(final CompletionException exception) {
    if (exception.getCause() instanceof Error error) {
      throw error;
    }
    return exception.getCause() instanceof RuntimeException runtimeException
        ? runtimeException
        : new IllegalStateException("single-flight operation failed", exception.getCause());
  }
}
