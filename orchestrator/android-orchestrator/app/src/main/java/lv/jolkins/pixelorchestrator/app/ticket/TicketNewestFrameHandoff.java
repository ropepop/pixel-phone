package lv.jolkins.pixelorchestrator.app.ticket;

/** One pending picture, plus the consumer-owned in-flight picture. No capture waits on encoding. */
final class TicketNewestFrameHandoff<T extends AutoCloseable> implements AutoCloseable {
  private T pending;
  private boolean closed;

  void offer(T value) throws Exception {
    T release;
    synchronized (this) {
      release = closed ? value : pending;
      if (!closed) pending = value;
      notifyAll();
    }
    if (release != null) release.close();
  }

  synchronized T take() throws InterruptedException {
    while (pending == null && !closed) wait();
    T value = pending;
    pending = null;
    return value;
  }

  synchronized boolean isClosed() { return closed; }

  /** No more capture inputs; let the consumer drain the final pending picture. */
  synchronized void finish() { closed = true; notifyAll(); }

  @Override public void close() throws Exception {
    T release;
    synchronized (this) {
      closed = true;
      release = pending;
      pending = null;
      notifyAll();
    }
    if (release != null) release.close();
  }
}
