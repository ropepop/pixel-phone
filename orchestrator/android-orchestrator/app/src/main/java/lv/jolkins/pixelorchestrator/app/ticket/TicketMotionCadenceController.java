package lv.jolkins.pixelorchestrator.app.ticket;

/**
 * Small, allocation-free cadence policy for the existing SDR encoder.
 *
 * <p>The caller supplies the number of changed luma samples from a persistent
 * thumbnail. Promotion is immediate; demotion is deliberately hysteretic so
 * a short quiet interval cannot make an interaction look frozen.</p>
 */
public final class TicketMotionCadenceController {
  public static final int STATIC_FPS = 1;
  public static final int MODERATE_FPS = 5;
  public static final int ACTIVE_FPS = 10;
  public static final long HIGH_MOTION_HYSTERESIS_MILLIS = 2_000L;
  public static final long STATIC_HYSTERESIS_MILLIS = 5_000L;

  private int targetFps = STATIC_FPS;
  private long belowHighSinceMillis = -1L;
  private long belowStaticSinceMillis = -1L;

  public int targetFps() {
    return targetFps;
  }

  public int update(
      int changedPixels,
      int totalPixels,
      long nowMillis,
      boolean priorityBurst,
      int ceilingFps) {
    int ceiling = normalizeCeiling(ceilingFps);
    if (ceiling <= STATIC_FPS) {
      targetFps = STATIC_FPS;
      resetQuietTimers();
      return targetFps;
    }
    if (priorityBurst) {
      targetFps = ceiling;
      resetQuietTimers();
      return targetFps;
    }
    int total = Math.max(1, totalPixels);
    int changed = Math.max(0, Math.min(changedPixels, total));
    boolean high = changed * 100L >= total * 3L;
    boolean moderate = changed * 400L >= total;
    if (high) {
      targetFps = Math.min(ACTIVE_FPS, ceiling);
      resetQuietTimers();
      return targetFps;
    }
    // Both moderate and static motion are below the high-motion threshold.
    // Keep this timer running across moderate samples so an active stream
    // cannot remain at 10 FPS forever after movement settles.
    if (belowHighSinceMillis < 0L) {
      belowHighSinceMillis = nowMillis;
    }
    if (moderate) {
      belowStaticSinceMillis = -1L;
      if (targetFps >= ACTIVE_FPS && nowMillis - belowHighSinceMillis >= HIGH_MOTION_HYSTERESIS_MILLIS) {
        targetFps = Math.min(MODERATE_FPS, ceiling);
      } else if (targetFps < MODERATE_FPS) {
        targetFps = Math.min(MODERATE_FPS, ceiling);
      }
      if (targetFps > ceiling) {
        targetFps = ceiling;
      }
      return targetFps;
    }

    if (targetFps >= ACTIVE_FPS && nowMillis - belowHighSinceMillis >= HIGH_MOTION_HYSTERESIS_MILLIS) {
      targetFps = Math.min(MODERATE_FPS, ceiling);
    }
    if (belowStaticSinceMillis < 0L) {
      belowStaticSinceMillis = nowMillis;
    }
    if (targetFps >= MODERATE_FPS && nowMillis - belowStaticSinceMillis >= STATIC_HYSTERESIS_MILLIS) {
      targetFps = STATIC_FPS;
    }
    if (targetFps > ceiling) {
      targetFps = ceiling;
    }
    return targetFps;
  }

  private static int normalizeCeiling(int fps) {
    if (fps >= ACTIVE_FPS) return ACTIVE_FPS;
    if (fps >= MODERATE_FPS) return MODERATE_FPS;
    return STATIC_FPS;
  }

  private void resetQuietTimers() {
    belowHighSinceMillis = -1L;
    belowStaticSinceMillis = -1L;
  }
}
