package com.medassist.ingestion.context;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small thread-safe adapter useful for local operation and tests. */
public final class InMemoryContextCache implements ContextCache {
  private final ConcurrentMap<ContextCacheKey, ContextCacheEntry> entries =
      new ConcurrentHashMap<>();

  @Override
  public Optional<ContextCacheEntry> get(final ContextCacheKey key) {
    return Optional.ofNullable(entries.get(key));
  }

  @Override
  public void put(final ContextCacheKey key, final ContextCacheEntry entry) {
    if (key == null) {
      throw new NullPointerException("key");
    }
    if (entry == null) {
      throw new NullPointerException("entry");
    }
    entries.compute(
        key,
        (ignored, existing) -> {
          if (existing != null && !existing.equals(entry)) {
            throw new ContextCacheConflictException("context cache content conflict");
          }
          return entry;
        });
  }
}
