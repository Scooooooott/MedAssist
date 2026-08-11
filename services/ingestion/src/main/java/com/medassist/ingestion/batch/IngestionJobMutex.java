package com.medassist.ingestion.batch;

import java.util.concurrent.atomic.AtomicBoolean;

public final class IngestionJobMutex {
  private final AtomicBoolean acquired = new AtomicBoolean();

  public boolean tryAcquire() {
    return acquired.compareAndSet(false, true);
  }

  public void release() {
    acquired.set(false);
  }
}
