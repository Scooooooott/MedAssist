package com.medassist.common.context;

/** Decorates work so execution context is validated, installed, and always cleared. */
@FunctionalInterface
public interface TaskDecorator {
  Runnable decorate(Runnable task);
}
