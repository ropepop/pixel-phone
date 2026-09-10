package lv.jolkins.pixelorchestrator.app.ticket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Transfers one asynchronous capture to its waiter, or releases it after abandonment. */
final class TicketCaptureResult<T> implements AutoCloseable {
  private final CountDownLatch ready = new CountDownLatch(1);
  private final Consumer<T> release;
  private boolean completed;
  private boolean closed;
  private T value;
  private Throwable failure;

  TicketCaptureResult(Consumer<T> release) { this.release = release; }

  interface Wrapper<T, R> { R wrap(T value) throws Exception; }

  <R> R awaitAndWrap(long timeout, TimeUnit unit, Wrapper<T, R> wrapper) throws Exception {
    T raw = await(timeout, unit);
    boolean transferred = false;
    try {
      R result = wrapper.wrap(raw);
      transferred = result != null;
      return result;
    } finally {
      if (!transferred && raw != null) release.accept(raw);
    }
  }

  void succeed(T result) {
    synchronized (this) {
      if (!closed && !completed) {
        value = result;
        completed = true;
        ready.countDown();
        return;
      }
    }
    if (result != null) release.accept(result);
  }

  synchronized void fail(Throwable error) {
    if (closed || completed) return;
    failure = error;
    completed = true;
    ready.countDown();
  }

  T await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    try {
      if (!ready.await(timeout, unit)) throw new TimeoutException("secure_screen_capture_timed_out");
      synchronized (this) {
        if (closed) throw new IllegalStateException("capture result already consumed or abandoned");
        if (failure != null) throw new ExecutionException("secure_screen_capture_failed", failure);
        T result = value;
        value = null;
        closed = true;
        return result;
      }
    } finally {
      close();
    }
  }

  @Override public void close() {
    T abandoned;
    synchronized (this) {
      closed = true;
      abandoned = value;
      value = null;
      ready.countDown();
    }
    if (abandoned != null) release.accept(abandoned);
  }
}
