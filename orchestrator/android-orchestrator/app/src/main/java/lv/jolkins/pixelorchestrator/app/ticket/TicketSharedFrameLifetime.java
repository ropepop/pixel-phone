package lv.jolkins.pixelorchestrator.app.ticket;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared read-only capture ownership; the last reader releases the underlying GPU resources. */
final class TicketSharedFrameLifetime implements AutoCloseable {
  private final AtomicInteger readers = new AtomicInteger(1);
  private final Runnable release;

  TicketSharedFrameLifetime(Runnable release) { this.release = release; }

  void retain() {
    while (true) {
      int current = readers.get();
      if (current <= 0) throw new IllegalStateException("capture already released");
      if (readers.compareAndSet(current, current + 1)) return;
    }
  }

  @Override public void close() {
    int remaining = readers.decrementAndGet();
    if (remaining == 0) release.run();
    else if (remaining < 0) throw new IllegalStateException("capture released twice");
  }
}
