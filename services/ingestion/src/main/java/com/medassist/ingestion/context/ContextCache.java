package com.medassist.ingestion.context;

import java.util.Optional;

/** Port for durable or distributed context-prefix caching. */
public interface ContextCache {
  Optional<ContextCacheEntry> get(ContextCacheKey key);

  void put(ContextCacheKey key, ContextCacheEntry entry);
}
