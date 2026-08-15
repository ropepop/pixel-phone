package lv.jolkins.pixelorchestrator.app.ticket;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TicketRootHardwareH264CaptureMain {
  private static final byte[] START_CODE = new byte[] { 0, 0, 0, 1 };
  private static final byte[] ACCESS_UNIT_DELIMITER = new byte[] { 0, 0, 0, 1, 9, 16 };
  private static final int SCREEN_CAPTURE_POLICY_CAPTURE = 1;
  private static final int SCREEN_CAPTURE_MODE_REQUIRE_OPTIMIZED = 1;
  private static final int VISIBILITY_SAMPLE_WIDTH = 12;
  private static final int VISIBILITY_SAMPLE_HEIGHT = 20;
  private static final long VISIBILITY_PROBE_INTERVAL_MILLIS = 2_000L;
  private static final long CONTROL_CODE_VISUAL_PROBE_MILLIS = 2_500L;
  private static final long CONTROL_CODE_VISUAL_GENERATED_HOLD_MILLIS = 1_200L;
  private static final long CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS = 150L;
  private static final long CONTROL_CODE_REQUEST_BURST_MAX_MILLIS = 20_000L;
  private static final String PNG_BASE64_BEGIN = "PNG_BASE64_BEGIN";
  private static final String PNG_BASE64_END = "PNG_BASE64_END";

  private TicketRootHardwareH264CaptureMain() {}

  public static void main(String[] args) throws Exception {
    int width = intArg(args, "--width", 0);
    int height = intArg(args, "--height", 0);
    int sourceWidth = intArg(args, "--source-width", width);
    int sourceHeight = intArg(args, "--source-height", height);
    int cropTopSource = intArg(args, "--crop-top-source", 0);
    int steadyFps = Math.max(1, intArg(args, "--fps", 10));
    int startupFps = Math.max(steadyFps, intArg(args, "--startup-fps", steadyFps));
    int controlCodeRequestFps = Math.max(steadyFps, intArg(args, "--control-code-request-fps", steadyFps));
    int startupFrames = Math.max(0, intArg(args, "--startup-frames", 0));
    int encoderFps = Math.max(controlCodeRequestFps, Math.max(steadyFps, startupFrames > 0 ? startupFps : steadyFps));
    int bitrate = Math.max(500_000, intArg(args, "--bitrate", 5_000_000));
    int keyframeMillis = Math.max(1, intArg(args, "--keyframe-interval-millis", 1_000));
    int frames = intArg(args, "--frames", 0);
    boolean pngBase64 = hasFlag(args, "--png-base64");
    if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
      throw new IllegalArgumentException("source and target dimensions are required");
    }
    cropTopSource = Math.max(0, Math.min(cropTopSource, Math.max(0, sourceHeight - 1)));

    exemptHiddenApis();
    if (pngBase64) {
      captureSecurePngBase64(sourceWidth, sourceHeight, width, height);
      return;
    }

    SurfaceCapture capture = new SecureScreenCapture(sourceWidth, sourceHeight);
    MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
    Surface inputSurface = null;
    OutputStream output = new BufferedOutputStream(System.out, 256 * 1024);
    AtomicBoolean syncFrameRequested = new AtomicBoolean(true);
    AtomicReference<ControlCodeVisualProbeRequest> controlCodeVisualProbeRequest =
      new AtomicReference<>(ControlCodeVisualProbeRequest.idle());
    AtomicLong controlCodeBurstUntilMillis = new AtomicLong(0L);
    Object frameWaitLock = new Object();
    Thread commandThread = null;
    try {
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      long configuredFrameIntervalMillis = Math.max(1L, Math.round(1000.0 / encoderFps));
      boolean allKeyFrames = keyframeMillis <= configuredFrameIntervalMillis + 1L;
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, encoderFps);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      format.setInteger(MediaFormat.KEY_LATENCY, 0);
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, encoderFps);
      if (supportsCbrBitrateMode(encoder)) {
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
      }
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, allKeyFrames ? 0 : Math.max(1, Math.round(keyframeMillis / 1000.0f)));
      format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
      format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
      format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
      format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      inputSurface = encoder.createInputSurface();
      encoder.start();
      commandThread = startCommandReader(
        syncFrameRequested,
        controlCodeVisualProbeRequest,
        controlCodeBurstUntilMillis,
        frameWaitLock,
        controlCodeRequestFps,
        startupFps
      );

      Rect sourceCrop = new Rect(0, cropTopSource, sourceWidth, sourceHeight);
      Rect destination = new Rect(0, 0, width, height);
      Paint paint = hardwareColorCorrectionPaint();
      int sent = 0;
      int startupVisibilityFrames = Math.max(1, startupFrames > 0 ? startupFrames : steadyFps);
      int startupPrimeInputs = 1;
      int blockedOrBlankFrames = 0;
      long lastMetricAt = 0L;
      long lastVisibilityProbeAt = 0L;
      boolean lastVisibilityVisible = true;
      while (frames <= 0 || sent < frames) {
        long started = SystemClock.elapsedRealtime();
        ControlCodeVisualProbeRequest visualProbeRequest = controlCodeVisualProbeRequest.get();
        boolean controlCodeVisualProbeActive = started <= visualProbeRequest.untilMillis.get();
        long controlCodeBurstUntil = controlCodeBurstUntilMillis.get();
        boolean controlCodeBurstActive = started <= controlCodeBurstUntil;
        if (
          !controlCodeBurstActive &&
          controlCodeBurstUntil > 0L &&
          controlCodeBurstUntilMillis.compareAndSet(controlCodeBurstUntil, 0L)
        ) {
          System.err.println("CONTROL_CODE_BURST state=expired fps_target=" + controlCodeRequestFps);
        }
        int currentTargetFps = controlCodeBurstActive
          ? controlCodeRequestFps
          : (startupFrames > 0 && sent < startupFrames
            ? startupFps
            : (controlCodeVisualProbeActive ? visualProbeRequest.targetFps : steadyFps));
        long frameIntervalMillis = Math.max(1L, Math.round(1000.0 / currentTargetFps));
        boolean explicitSyncFrame = syncFrameRequested.getAndSet(false);
        if (sent == 0 || allKeyFrames || explicitSyncFrame) {
          requestSyncFrame(encoder);
        }
        long captureStarted = SystemClock.elapsedRealtime();
        CapturedFrame source = capture.capture();
        long captureFinished = SystemClock.elapsedRealtime();
        if (source == null) {
          throw new IllegalStateException("ScreenCapture returned no bitmap");
        }
        boolean visible = lastVisibilityVisible;
        int inputPosts = 0;
        long drawStarted = 0L;
        long drawFinished = 0L;
        long encodeStarted = 0L;
        try {
          boolean shouldProbeVisibility = sent < startupVisibilityFrames || started - lastVisibilityProbeAt >= VISIBILITY_PROBE_INTERVAL_MILLIS;
          if (shouldProbeVisibility) {
            lastVisibilityProbeAt = started;
            visible = frameLooksVisible(source.bitmap, sourceCrop);
            lastVisibilityVisible = visible;
            if (visible) {
              blockedOrBlankFrames = 0;
            } else {
              blockedOrBlankFrames += 1;
              boolean failure = sent >= startupVisibilityFrames && blockedOrBlankFrames >= Math.max(4, currentTargetFps / 2);
              System.err.println(
                "VISIBILITY result=blocked invisible_frames=" + blockedOrBlankFrames +
                  " failure=" + failure +
                  " reason=secure_screen_capture_blocked_or_blank"
              );
              if (failure) {
                throw new IllegalStateException("secure_screen_capture_blocked_or_blank");
              }
            }
          }
          if (controlCodeVisualProbeActive) {
            String state = classifyControlCodeVisualState(
              source.bitmap,
              sourceCrop,
              visualProbeRequest.cleanup,
              visualProbeRequest.submitLayout
            );
            if (state.equals("generated")) {
              if (visualProbeRequest.generated.compareAndSet(false, true)) {
                visualProbeRequest.lastReportMillis.set(started);
                extendUntil(visualProbeRequest.untilMillis, started + CONTROL_CODE_VISUAL_GENERATED_HOLD_MILLIS);
                System.err.println(
                  "CONTROL_CODE_VISUAL result=generated reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                    " probe_id=" + visualProbeRequest.id +
                    " method=h264_bitmap_probe"
                );
              } else if (started - visualProbeRequest.lastReportMillis.get() >= CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS) {
                visualProbeRequest.lastReportMillis.set(started);
                System.err.println(
                  "CONTROL_CODE_VISUAL result=generated reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                    " probe_id=" + visualProbeRequest.id +
                    " method=h264_bitmap_probe"
                );
              }
            } else if (started - visualProbeRequest.lastReportMillis.get() >= CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS) {
              visualProbeRequest.lastReportMillis.set(started);
              System.err.println(
                "CONTROL_CODE_VISUAL result=" + state +
                  " reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                  " probe_id=" + visualProbeRequest.id +
                  " method=h264_bitmap_probe"
              );
            }
          }
          inputPosts = sent == 0 ? startupPrimeInputs : 1;
          drawStarted = SystemClock.elapsedRealtime();
          for (int post = 0; post < inputPosts; post++) {
            drawBitmap(inputSurface, source.bitmap, sourceCrop, destination, paint);
          }
          drawFinished = SystemClock.elapsedRealtime();
          long drainTimeoutUs = sent < Math.max(3, startupFrames)
            ? Math.min(80_000L, Math.max(25_000L, frameIntervalMillis * 1_000L))
            : 10_000L;
          encodeStarted = SystemClock.elapsedRealtime();
          int drained = drainEncoder(encoder, output, false, drainTimeoutUs);
          if (drained == 0 && sent == 0) {
            long remainingFrameBudgetMillis = Math.max(1L, frameIntervalMillis - (SystemClock.elapsedRealtime() - started));
            long startupDrainDeadline = SystemClock.elapsedRealtime() + Math.min(300L, Math.max(80L, remainingFrameBudgetMillis));
            while (drained == 0 && SystemClock.elapsedRealtime() < startupDrainDeadline) {
              long remainingUs = Math.max(1L, (startupDrainDeadline - SystemClock.elapsedRealtime()) * 1_000L);
              drained += drainEncoder(encoder, output, false, Math.min(80_000L, remainingUs));
            }
          }
          output.flush();
        } finally {
          // lockHardwareCanvas/unlockCanvasAndPost queues GPU work asynchronously. Keep the
          // hardware-backed capture result alive until this frame's encoder drain;
          // closing it immediately after posting can make the encoder reuse an older frame.
          source.close();
        }
        long encodeFinished = SystemClock.elapsedRealtime();
        sent += inputPosts;
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (sent <= 3 || started - lastMetricAt >= 1_000L) {
          lastMetricAt = started;
          System.err.println(
            "METRIC capture_ms=" + (captureFinished - captureStarted) +
              " draw_ms=" + (drawFinished - drawStarted) +
              " encode_ms=" + (encodeFinished - encodeStarted) +
              " frame_ms=" + elapsed +
              " fps_target=" + currentTargetFps +
              " steady_fps=" + steadyFps +
              " startup_fps=" + startupFps +
              " control_code_request_fps=" + controlCodeRequestFps +
              " startup_frames=" + startupFrames +
              " visibility=" + (visible ? "visible" : "blocked") +
              " secure_layers=true protected_content=true method=secure_screen_capture"
          );
        }
        long sleep = frameIntervalMillis - elapsed;
        if (sleep > 0L) {
          waitForNextFrame(frameWaitLock, sleep);
        }
      }
      encoder.signalEndOfInputStream();
      drainEncoder(encoder, output, true, 100_000L);
      output.flush();
    } finally {
      if (commandThread != null) {
        commandThread.interrupt();
      }
      if (inputSurface != null) {
        inputSurface.release();
      }
      runQuietly(encoder::stop);
      encoder.release();
      output.flush();
    }
  }

  private static Thread startCommandReader(
    AtomicBoolean syncFrameRequested,
    AtomicReference<ControlCodeVisualProbeRequest> controlCodeVisualProbeRequest,
    AtomicLong controlCodeBurstUntilMillis,
    Object frameWaitLock,
    int controlCodeRequestFps,
    int startupFps
  ) {
    Thread thread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String cmd = line.trim();
          if (cmd.equals("keyframe")) {
            syncFrameRequested.set(true);
            wakeFrameLoop(frameWaitLock);
          } else if (cmd.equals("control_code_burst_start")) {
            controlCodeBurstUntilMillis.set(SystemClock.elapsedRealtime() + CONTROL_CODE_REQUEST_BURST_MAX_MILLIS);
            System.err.println("CONTROL_CODE_BURST state=started fps_target=" + controlCodeRequestFps);
            syncFrameRequested.set(true);
            wakeFrameLoop(frameWaitLock);
          } else if (cmd.equals("control_code_burst_stop")) {
            controlCodeBurstUntilMillis.set(0L);
            ControlCodeVisualProbeRequest current = controlCodeVisualProbeRequest.get();
            if (!current.cleanup) {
              current.untilMillis.set(0L);
            }
            System.err.println("CONTROL_CODE_BURST state=stopped fps_target=" + controlCodeRequestFps);
            wakeFrameLoop(frameWaitLock);
          } else if (
            cmd.startsWith("control_code_visual_probe:") ||
            cmd.startsWith("control_code_request_visual_probe:") ||
            cmd.startsWith("control_code_submit_visual_probe:") ||
            cmd.startsWith("control_code_cleanup_visual_probe:") ||
            cmd.startsWith("ticket_detail_visual_probe:")
          ) {
            int separator = cmd.lastIndexOf(':');
            long probeId = parseLong(cmd.substring(separator + 1), 0L);
            if (probeId <= 0L) {
              continue;
            }
            boolean cleanup = cmd.startsWith("control_code_cleanup_visual_probe:");
            boolean submitLayout = cmd.startsWith("control_code_submit_visual_probe:");
            boolean ticketDetail = cmd.startsWith("ticket_detail_visual_probe:");
            boolean requestCadence = cmd.startsWith("control_code_request_visual_probe:") || submitLayout;
            controlCodeVisualProbeRequest.set(new ControlCodeVisualProbeRequest(
              probeId,
              ticketDetail ? "ticket_detail" : (cleanup ? "control_code_cleanup" : (submitLayout ? "control_code_submit_layout" : "control_code_after_ok")),
              cleanup,
              submitLayout,
              requestCadence ? controlCodeRequestFps : startupFps,
              SystemClock.elapsedRealtime() + CONTROL_CODE_VISUAL_PROBE_MILLIS
            ));
            syncFrameRequested.set(true);
            wakeFrameLoop(frameWaitLock);
          }
        }
      } catch (Throwable ignored) {
        // Closing stdin is expected when the Kotlin-side engine stops the helper.
      }
    }, "ticket-h264-command-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static final class ControlCodeVisualProbeRequest {
    final long id;
    final String reason;
    final boolean cleanup;
    final boolean submitLayout;
    final int targetFps;
    final AtomicLong untilMillis;
    final AtomicLong lastReportMillis = new AtomicLong(0L);
    final AtomicBoolean generated = new AtomicBoolean(false);

    ControlCodeVisualProbeRequest(
      long id,
      String reason,
      boolean cleanup,
      boolean submitLayout,
      int targetFps,
      long untilMillis
    ) {
      this.id = id;
      this.reason = reason;
      this.cleanup = cleanup;
      this.submitLayout = submitLayout;
      this.targetFps = Math.max(1, targetFps);
      this.untilMillis = new AtomicLong(untilMillis);
    }

    static ControlCodeVisualProbeRequest idle() {
      return new ControlCodeVisualProbeRequest(0L, "idle", false, false, 1, 0L);
    }
  }

  private static void waitForNextFrame(Object frameWaitLock, long millis) throws InterruptedException {
    synchronized (frameWaitLock) {
      frameWaitLock.wait(millis);
    }
  }

  private static void wakeFrameLoop(Object frameWaitLock) {
    synchronized (frameWaitLock) {
      frameWaitLock.notifyAll();
    }
  }

  private static void extendUntil(AtomicLong value, long untilMillis) {
    long current;
    do {
      current = value.get();
      if (current >= untilMillis) {
        return;
      }
    } while (!value.compareAndSet(current, untilMillis));
  }

  private static void requestSyncFrame(MediaCodec encoder) {
    try {
      Bundle params = new Bundle();
      params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      encoder.setParameters(params);
    } catch (Throwable ignored) {
      // If the codec rejects an explicit sync-frame request, the configured GOP still applies.
    }
  }

  private static boolean supportsCbrBitrateMode(MediaCodec encoder) {
    try {
      MediaCodecInfo.CodecCapabilities capabilities =
        encoder.getCodecInfo().getCapabilitiesForType("video/avc");
      MediaCodecInfo.EncoderCapabilities encoderCapabilities = capabilities.getEncoderCapabilities();
      return encoderCapabilities != null &&
        encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void drawBitmap(Surface surface, Bitmap source, Rect sourceCrop, Rect destination, Paint paint) {
    try {
      drawBitmapOnCanvas(surface, source, sourceCrop, destination, paint);
    } catch (Throwable error) {
      throw new IllegalStateException("Unable to draw captured frame into hardware encoder surface", error);
    }
  }

  private static Paint hardwareColorCorrectionPaint() {
    Paint paint = new Paint();
    paint.setFilterBitmap(false);
    paint.setDither(false);
    paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
      0f, 0f, 1f, 0f, 0f,
      0f, 1f, 0f, 0f, 0f,
      1f, 0f, 0f, 0f, 0f,
      0f, 0f, 0f, 1f, 0f
    })));
    return paint;
  }

  private static void drawBitmapOnCanvas(
    Surface surface,
    Bitmap source,
    Rect sourceCrop,
    Rect destination,
    Paint paint
  ) {
    Canvas canvas = null;
    try {
      canvas = surface.lockHardwareCanvas();
      canvas.drawBitmap(source, sourceCrop, destination, paint);
    } finally {
      if (canvas != null) {
        surface.unlockCanvasAndPost(canvas);
      }
    }
  }

  private static void captureSecurePngBase64(
      int sourceWidth,
      int sourceHeight,
      int targetWidth,
      int targetHeight) throws Exception {
    SurfaceCapture capture = new SecureScreenCapture(sourceWidth, sourceHeight);
    CapturedFrame frame = capture.capture();
    if (frame == null || frame.bitmap == null) {
      throw new IllegalStateException("secure_screen_capture_returned_no_bitmap");
    }
    Bitmap readableSource = frame.bitmap;
    Bitmap encodedSource = null;
    boolean copiedSource = false;
    try {
      if (frame.bitmap.getConfig() == Bitmap.Config.HARDWARE) {
        readableSource = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
        copiedSource = true;
      }
      if (targetWidth > 0 && targetHeight > 0 &&
          (targetWidth != readableSource.getWidth() || targetHeight != readableSource.getHeight())) {
        encodedSource = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(encodedSource);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(
          readableSource,
          new Rect(0, 0, readableSource.getWidth(), readableSource.getHeight()),
          new Rect(0, 0, targetWidth, targetHeight),
          paint
        );
      } else {
        encodedSource = readableSource;
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!encodedSource.compress(Bitmap.CompressFormat.PNG, 100, output)) {
        throw new IllegalStateException("secure_screen_capture_png_encode_failed");
      }
      byte[] bytes = output.toByteArray();
      System.out.println(PNG_BASE64_BEGIN);
      System.out.print(Base64.encodeToString(bytes, Base64.NO_WRAP));
      System.out.println();
      System.out.println(PNG_BASE64_END);
      System.err.println(
        "PNG_METRIC bytes=" + bytes.length +
          " source_width=" + sourceWidth +
          " source_height=" + sourceHeight +
          " target_width=" + encodedSource.getWidth() +
          " target_height=" + encodedSource.getHeight() +
          " method=secure_screen_capture"
      );
    } finally {
      if (encodedSource != null && encodedSource != readableSource) {
        encodedSource.recycle();
      }
      if (copiedSource && readableSource != null) {
        readableSource.recycle();
      }
      frame.close();
    }
  }

  private static boolean frameLooksVisible(Bitmap source, Rect sourceCrop) {
    Bitmap readableSource = source;
    boolean copiedSource = false;
    Bitmap probe = Bitmap.createBitmap(VISIBILITY_SAMPLE_WIDTH, VISIBILITY_SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
    try {
      if (source.getConfig() == Bitmap.Config.HARDWARE) {
        readableSource = source.copy(Bitmap.Config.ARGB_8888, false);
        copiedSource = true;
      }
      Canvas canvas = new Canvas(probe);
      Paint paint = new Paint();
      paint.setFilterBitmap(false);
      paint.setDither(false);
      canvas.drawColor(Color.BLACK);
      canvas.drawBitmap(readableSource, sourceCrop, new Rect(0, 0, VISIBILITY_SAMPLE_WIDTH, VISIBILITY_SAMPLE_HEIGHT), paint);
      int sampled = 0;
      int bright = 0;
      int dark = 0;
      int min = 255;
      int max = 0;
      long sum = 0L;
      for (int y = 0; y < VISIBILITY_SAMPLE_HEIGHT; y++) {
        for (int x = 0; x < VISIBILITY_SAMPLE_WIDTH; x++) {
          int pixel = probe.getPixel(x, y);
          int red = (pixel >> 16) & 0xff;
          int green = (pixel >> 8) & 0xff;
          int blue = pixel & 0xff;
          int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
          min = Math.min(min, luminance);
          max = Math.max(max, luminance);
          sum += luminance;
          if (luminance >= 180) {
            bright += 1;
          }
          if (luminance <= 60) {
            dark += 1;
          }
          sampled += 1;
        }
      }
      if (sampled == 0) {
        return false;
      }
      double mean = sum / (double) sampled;
      double brightRatio = bright / (double) sampled;
      double darkRatio = dark / (double) sampled;
      int range = max - min;
      return range >= 35 && brightRatio >= 0.08 && darkRatio >= 0.02 && mean >= 35.0;
    } finally {
      probe.recycle();
      if (copiedSource && readableSource != null) {
        readableSource.recycle();
      }
    }
  }

  private static String classifyControlCodeVisualState(
    Bitmap source,
    Rect sourceCrop,
    boolean cleanupProbe,
    boolean submitLayoutProbe
  ) {
    Bitmap readableSource = source;
    boolean copiedSource = false;
    int probeWidth = submitLayoutProbe
      ? TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_WIDTH
      : TicketControlCodeVisualClassifier.SAMPLE_WIDTH;
    int probeHeight = submitLayoutProbe
      ? TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_HEIGHT
      : TicketControlCodeVisualClassifier.SAMPLE_HEIGHT;
    Bitmap probe = Bitmap.createBitmap(
      probeWidth,
      probeHeight,
      Bitmap.Config.ARGB_8888
    );
    try {
      if (source.getConfig() == Bitmap.Config.HARDWARE) {
        readableSource = source.copy(Bitmap.Config.ARGB_8888, false);
        copiedSource = true;
      }
      Canvas canvas = new Canvas(probe);
      // Classification reads the capture bitmap before it is drawn into the MediaCodec surface.
      // The source bitmap already has normal channel order; the red/blue correction belongs only
      // to the later encoder-surface draw. Applying it here turns ViVi's orange OK button blue.
      Paint paint = new Paint();
      paint.setFilterBitmap(false);
      paint.setDither(false);
      canvas.drawColor(Color.BLACK);
      canvas.drawBitmap(
        readableSource,
        sourceCrop,
        new Rect(0, 0, probeWidth, probeHeight),
        paint
      );
      int[] pixels = new int[probeWidth * probeHeight];
      probe.getPixels(
        pixels,
        0,
        probeWidth,
        0,
        0,
        probeWidth,
        probeHeight
      );
      if (submitLayoutProbe) {
        return TicketControlCodeVisualClassifier.classifySubmitLayout(pixels);
      }
      return cleanupProbe
        ? TicketControlCodeVisualClassifier.classifyForCleanup(pixels)
        : TicketControlCodeVisualClassifier.classify(pixels);
    } finally {
      probe.recycle();
      if (copiedSource && readableSource != null) {
        readableSource.recycle();
      }
    }
  }

  private static String safeDiagnosticValue(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
  }

  private static int drainEncoder(MediaCodec encoder, OutputStream output, boolean endOfStream, long timeoutUs)
    throws Exception {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int drained = 0;
    while (true) {
      int index = encoder.dequeueOutputBuffer(info, timeoutUs);
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (endOfStream) {
          continue;
        }
        return drained;
      }
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
        continue;
      }
      if (index < 0) {
        continue;
      }
      ByteBuffer buffer = encoder.getOutputBuffer(index);
      if (buffer != null && info.size > 0) {
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        byte[] data = new byte[info.size];
        buffer.get(data);
        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || keyFrame || info.size > 0) {
          writeAnnexB(output, data);
          output.write(ACCESS_UNIT_DELIMITER);
          drained += 1;
        }
      }
      boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      encoder.releaseOutputBuffer(index, false);
      if (eos) {
        return drained;
      }
      if (!endOfStream) {
        timeoutUs = 0L;
      }
    }
  }

  private static void writeAnnexB(OutputStream output, byte[] data) throws Exception {
    if (data.length == 0) {
      return;
    }
    if (startsWithStartCode(data)) {
      output.write(data);
      return;
    }
    int offset = 0;
    boolean converted = false;
    while (offset + 4 <= data.length) {
      int length = ByteBuffer.wrap(data, offset, 4).getInt();
      offset += 4;
      if (length <= 0 || offset + length > data.length) {
        converted = false;
        break;
      }
      output.write(START_CODE);
      output.write(data, offset, length);
      offset += length;
      converted = true;
    }
    if (!converted || offset != data.length) {
      output.write(START_CODE);
      output.write(data);
    }
  }

  private static boolean startsWithStartCode(byte[] data) {
    return data.length >= 4 &&
      data[0] == 0 &&
      data[1] == 0 &&
      ((data[2] == 1) || (data[2] == 0 && data[3] == 1));
  }

  private static int intArg(String[] args, String name, int fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (name.equals(args[i])) {
        return Integer.parseInt(args[i + 1]);
      }
    }
    return fallback;
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean hasFlag(String[] args, String name) {
    for (String arg : args) {
      if (name.equals(arg)) {
        return true;
      }
    }
    return false;
  }

  private static void exemptHiddenApis() {
    try {
      Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
      Method getRuntime = runtimeClass.getDeclaredMethod("getRuntime");
      Method setHiddenApiExemptions = runtimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
      Object runtime = getRuntime.invoke(null);
      setHiddenApiExemptions.invoke(runtime, (Object) new String[] { "L" });
    } catch (Throwable ignored) {
      // Best effort for rooted app_process hidden API access.
    }
  }

  private static void runQuietly(ThrowingRunnable block) {
    try {
      block.run();
    } catch (Throwable ignored) {
      // Cleanup path only.
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private interface SurfaceCapture {
    CapturedFrame capture() throws Exception;
  }

  private static final class CapturedFrame implements AutoCloseable {
    private final Bitmap bitmap;
    private final Object result;
    private final HardwareBuffer hardwareBuffer;

    CapturedFrame(Bitmap bitmap, Object result, HardwareBuffer hardwareBuffer) {
      this.bitmap = bitmap;
      this.result = result;
      this.hardwareBuffer = hardwareBuffer;
    }

    @Override
    public void close() {
      if (bitmap != null) {
        bitmap.recycle();
      }
      closeScreenCaptureResult(result);
      if (hardwareBuffer != null && !hardwareBuffer.isClosed()) {
        hardwareBuffer.close();
      }
    }
  }

  @TargetApi(31)
  private static final class SecureScreenCapture implements SurfaceCapture {
    private final Method capture;
    private final Method getHardwareBuffer;
    private final Method getColorSpace;
    private final Object args;
    private final Executor directExecutor = Runnable::run;

    SecureScreenCapture(int width, int height) throws Exception {
      Class<?> screenCapture = Class.forName("android.window.ScreenCapture");
      Class<?> paramsClass = Class.forName("android.window.ScreenCapture$ScreenCaptureParams");
      Class<?> builderClass = Class.forName("android.window.ScreenCapture$ScreenCaptureParams$Builder");
      Constructor<?> constructor = builderClass.getDeclaredConstructor(int.class);
      Object builder = constructor.newInstance(0);
      invokeIfPresent(builder, "setIncludeSystemOverlays", new Class<?>[] { boolean.class }, true);
      invokeIfPresent(builder, "setPixelFormat", new Class<?>[] { int.class }, PixelFormat.RGBA_8888);
      if (screenCaptureOptimizationEnabled(screenCapture)) {
        invokeIfPresent(builder, "setCaptureMode", new Class<?>[] { int.class }, SCREEN_CAPTURE_MODE_REQUIRE_OPTIMIZED);
      }
      invokeIfPresent(builder, "setPreserveDisplayColors", new Class<?>[] { boolean.class }, false);
      invokeIfPresent(builder, "setSecureContentPolicy", new Class<?>[] { int.class }, SCREEN_CAPTURE_POLICY_CAPTURE);
      invokeIfPresent(builder, "setProtectedContentPolicy", new Class<?>[] { int.class }, SCREEN_CAPTURE_POLICY_CAPTURE);
      invokeIfPresent(builder, "setUseDisplayInstallationOrientation", new Class<?>[] { boolean.class }, false);
      args = builderClass.getDeclaredMethod("build").invoke(builder);
      capture = screenCapture.getDeclaredMethod(
        "capture",
        paramsClass,
        Executor.class,
        OutcomeReceiver.class
      );
      Class<?> resultClass = Class.forName("android.window.ScreenCapture$ScreenCaptureResult");
      getHardwareBuffer = resultClass.getDeclaredMethod("getHardwareBuffer");
      getColorSpace = resultClass.getDeclaredMethod("getColorSpace");
    }

    @Override
    public CapturedFrame capture() throws Exception {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Object> result = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      OutcomeReceiver<Object, Throwable> receiver = new OutcomeReceiver<Object, Throwable>() {
        @Override
        public void onResult(Object value) {
          result.set(value);
          latch.countDown();
        }

        @Override
        public void onError(Throwable error) {
          failure.set(error);
          latch.countDown();
        }
      };
      capture.invoke(null, args, directExecutor, receiver);
      if (!latch.await(350, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("secure_screen_capture_timed_out");
      }
      if (failure.get() != null) {
        throw new IllegalStateException("secure_screen_capture_failed", failure.get());
      }
      Object value = result.get();
      if (value == null) {
        return null;
      }
      HardwareBuffer buffer = (HardwareBuffer) getHardwareBuffer.invoke(value);
      if (buffer == null) {
        closeScreenCaptureResult(value);
        return null;
      }
      ColorSpace colorSpace = (ColorSpace) getColorSpace.invoke(value);
      Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
      if (bitmap == null) {
        closeScreenCaptureResult(value);
        if (!buffer.isClosed()) {
          buffer.close();
        }
        return null;
      }
      return new CapturedFrame(bitmap, value, buffer);
    }
  }

  private static boolean screenCaptureOptimizationEnabled(Class<?> screenCapture) {
    try {
      Method method = screenCapture.getDeclaredMethod("isScreenCaptureOptimizationEnabled");
      Object value = method.invoke(null);
      return value instanceof Boolean && (Boolean) value;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void invokeIfPresent(Object target, String name, Class<?>[] types, Object... args) {
    try {
      Method method = target.getClass().getDeclaredMethod(name, types);
      method.invoke(target, args);
    } catch (NoSuchMethodException ignored) {
      // Android release dependent.
    } catch (Throwable error) {
      throw new IllegalStateException("Failed invoking " + name, error);
    }
  }

  private static void closeScreenCaptureResult(Object value) {
    if (value == null) {
      return;
    }
    try {
      Method close = value.getClass().getDeclaredMethod("close");
      close.invoke(value);
    } catch (Throwable ignored) {
      // ScreenCaptureResult is not closeable on every Android release.
    }
  }
}
