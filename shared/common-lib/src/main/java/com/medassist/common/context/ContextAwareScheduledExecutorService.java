package com.medassist.common.context;

import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Scheduled executor facade that applies the request and tracing context to every task. */
final class ContextAwareScheduledExecutorService extends AbstractExecutorService
    implements ScheduledExecutorService {
  private final ScheduledExecutorService delegate;
  private final TaskDecorator decorator;

  ContextAwareScheduledExecutorService(
      final ScheduledExecutorService delegate, final TaskDecorator decorator) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.decorator = Objects.requireNonNull(decorator, "decorator");
  }

  @Override
  public void execute(final Runnable command) {
    delegate.execute(decorator.decorate(command));
  }

  @Override
  public ScheduledFuture<?> schedule(
      final Runnable command, final long delay, final TimeUnit unit) {
    return delegate.schedule(decorator.decorate(command), delay, unit);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(
      final Callable<V> callable, final long delay, final TimeUnit unit) {
    final FutureTask<V> result = new FutureTask<>(callable);
    final ScheduledFuture<?> scheduled = delegate.schedule(decorator.decorate(result), delay, unit);
    return new DelegatingScheduledFuture<>(scheduled, result);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
    return delegate.scheduleAtFixedRate(decorator.decorate(command), initialDelay, period, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
    return delegate.scheduleWithFixedDelay(decorator.decorate(command), initialDelay, delay, unit);
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public java.util.List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(final long timeout, final TimeUnit unit)
      throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  private static final class DelegatingScheduledFuture<V> implements ScheduledFuture<V> {
    private final ScheduledFuture<?> scheduled;
    private final Future<V> result;

    private DelegatingScheduledFuture(final ScheduledFuture<?> scheduled, final Future<V> result) {
      this.scheduled = scheduled;
      this.result = result;
    }

    @Override
    public long getDelay(final TimeUnit unit) {
      return scheduled.getDelay(unit);
    }

    @Override
    public int compareTo(final java.util.concurrent.Delayed other) {
      return scheduled.compareTo(other);
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      final boolean cancelled = result.cancel(mayInterruptIfRunning);
      return scheduled.cancel(mayInterruptIfRunning) || cancelled;
    }

    @Override
    public boolean isCancelled() {
      return result.isCancelled() || scheduled.isCancelled();
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public V get() throws java.util.concurrent.ExecutionException, InterruptedException {
      return result.get();
    }

    @Override
    public V get(final long timeout, final TimeUnit unit)
        throws java.util.concurrent.ExecutionException,
            InterruptedException,
            java.util.concurrent.TimeoutException {
      return result.get(timeout, unit);
    }
  }
}
