// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.concurrency;

import com.intellij.concurrency.ThreadContext;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.concurrency.SchedulingWrapper.MyScheduledFutureTask;
import kotlinx.coroutines.CompletableDeferred;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.Job;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static kotlinx.coroutines.CompletableDeferredKt.CompletableDeferred;
import static kotlinx.coroutines.JobKt.Job;

@Internal
public final class Propagation {

  static class Holder {
    private static boolean propagateThreadContext = Registry.is("ide.propagate.context");
    private static boolean propagateThreadCancellation = Registry.is("ide.propagate.cancellation");
  }

  @TestOnly
  static void runTestWithPropagationEnabled(@NotNull Runnable runnable) {
    boolean propagateThreadContext = Holder.propagateThreadContext;
    boolean propagateThreadCancellation = Holder.propagateThreadCancellation;
    Holder.propagateThreadContext = true;
    Holder.propagateThreadCancellation = true;
    try {
      runnable.run();
    }
    finally {
      Holder.propagateThreadContext = propagateThreadContext;
      Holder.propagateThreadCancellation = propagateThreadCancellation;
    }
  }

  private Propagation() { }

  static boolean propagateThreadContext() {
    return Holder.propagateThreadContext;
  }

  public static boolean propagateCancellation() {
    return Holder.propagateThreadCancellation;
  }

  static @NotNull Runnable handleCommand(@NotNull Runnable command) {
    if (propagateCancellation()) {
      //noinspection TestOnlyProblems
      Job currentJob = Cancellation.currentJob();
      CompletableJob childJob = Job(currentJob);
      return handleContext(new CancellationRunnable(childJob, command));
    }
    else {
      return handleContext(command);
    }
  }

  static <V> @NotNull FutureTask<V> handleTask(@NotNull Callable<V> callable) {
    if (propagateCancellation()) {
      //noinspection TestOnlyProblems
      Job currentJob = Cancellation.currentJob();
      CompletableDeferred<V> childDeferred = CompletableDeferred(currentJob);
      CancellationCallable<V> cancellationCallable = new CancellationCallable<>(childDeferred, callable);
      return new CancellationFutureTask<>(childDeferred, handleContext(cancellationCallable));
    }
    else {
      return new FutureTask<>(handleContext(callable));
    }
  }

  static <V> @NotNull MyScheduledFutureTask<V> handleScheduledFutureTask(
    @NotNull SchedulingWrapper wrapper,
    @NotNull Callable<V> callable,
    long ns
  ) {
    if (propagateCancellation()) {
      //noinspection TestOnlyProblems
      Job currentJob = Cancellation.currentJob();
      CompletableDeferred<V> childDeferred = CompletableDeferred(currentJob);
      CancellationCallable<V> cancellationCallable = new CancellationCallable<>(childDeferred, callable);
      return wrapper.new CancellationScheduledFutureTask<>(childDeferred, handleContext(cancellationCallable), ns);
    }
    else {
      return wrapper.new MyScheduledFutureTask<>(handleContext(callable), ns);
    }
  }

  static @NotNull MyScheduledFutureTask<?> handlePeriodicScheduledFutureTask(
    @NotNull SchedulingWrapper wrapper,
    @NotNull Runnable runnable,
    long ns,
    long period
  ) {
    if (propagateCancellation()) {
      //noinspection TestOnlyProblems
      Job currentJob = Cancellation.currentJob();
      CompletableJob childJob = Job(currentJob);
      PeriodicCancellationRunnable cancellationRunnable = new PeriodicCancellationRunnable(childJob, runnable);
      return wrapper.new CancellationScheduledFutureTask<>(childJob, handleContext(cancellationRunnable), ns, period);
    }
    else {
      return wrapper.new MyScheduledFutureTask<Void>(handleContext(runnable), null, ns, period);
    }
  }

  public static @NotNull Runnable handleContext(@NotNull Runnable runnable) {
    if (propagateThreadContext()) {
      return ThreadContext.captureThreadContext(runnable);
    }
    else {
      return runnable;
    }
  }

  private static <V> @NotNull Callable<V> handleContext(@NotNull Callable<V> callable) {
    if (propagateThreadContext()) {
      return ThreadContext.captureThreadContext(callable);
    }
    else {
      return callable;
    }
  }
}
