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
import android.view.Surface;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
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
  private static final int SCREEN_CAPTURE_POLICY_CAPTURE = 1;
  private static final int SCREEN_CAPTURE_MODE_REQUIRE_OPTIMIZED = 1;
  private static final int VISIBILITY_SAMPLE_WIDTH = 12;
  private static final int VISIBILITY_SAMPLE_HEIGHT = 20;
  private static final long VISIBILITY_PROBE_INTERVAL_MILLIS = 2_000L;
  private static final long CONTROL_CODE_VISUAL_PROBE_MILLIS = 2_500L;
  private static final long CONTROL_CODE_VISUAL_GENERATED_HOLD_MILLIS = 1_200L;
  private static final long CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS = 150L;
  private static final long STARTUP_PRIMER_DRAIN_POLL_MILLIS = 20L;
  private static final long STARTUP_PRIMER_FINAL_DRAIN_MILLIS = 300L;
  private static final long STARTUP_PRIMER_SETTLE_MILLIS = 100L;
  private static final long ARM_ACTIVATION_TIMEOUT_MILLIS = 5_000L;
  private static final long ENCODER_INPUT_TIMEOUT_US = 3_000_000L;
  // The public viewer is an SDR canvas. Keep the encoder metadata truthful and apply only a
  // conservative display-referred lift here, after correcting the capture surface's R/B order.
  // No offset is used so black UI surfaces remain black instead of turning gray.
  private static final float HIGH_BRIGHTNESS_SDR_RED_GAIN = 1.08f;
  private static final float HIGH_BRIGHTNESS_SDR_GREEN_GAIN = 1.05f;
  private static final float HIGH_BRIGHTNESS_SDR_BLUE_GAIN = 1.03f;

  private TicketRootHardwareH264CaptureMain() {}

  public static void main(String[] args) throws Exception {
    int width = intArg(args, "--width", 0);
    int height = intArg(args, "--height", 0);
    int sourceWidth = intArg(args, "--source-width", width);
    int sourceHeight = intArg(args, "--source-height", height);
    int cropLeftSource = intArg(args, "--crop-left-source", 0);
    int cropTopSource = intArg(args, "--crop-top-source", 0);
    int cropRightSource = intArg(args, "--crop-right-source", 0);
    int cropBottomSource = intArg(args, "--crop-bottom-source", 0);
    int captureFps = intArg(args, "--fps", TicketCaptureCadenceScheduler.FIXED_FPS);
    if (captureFps != TicketCaptureCadenceScheduler.FIXED_FPS) {
      throw new IllegalArgumentException("fixed all-intra capture requires 1 FPS");
    }
    int encoderFps = TicketCaptureCadenceScheduler.FIXED_FPS;
    int bitrate = Math.max(
      500_000,
      intArg(args, "--bitrate", TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE)
    );
    int frames = intArg(args, "--frames", 0);
    boolean awaitActivation = hasFlag(args, "--await-activation");
    long activationToken = longArg(args, "--activation-token", 0L);
    long codecGeneration = longArg(args, "--codec-generation", 0L);
    if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
      throw new IllegalArgumentException("source and target dimensions are required");
    }
    if (codecGeneration <= 0L) {
      throw new IllegalArgumentException("codec generation is required");
    }
    cropLeftSource = Math.max(0, Math.min(cropLeftSource, Math.max(0, sourceWidth - 1)));
    cropRightSource = Math.max(
      0,
      Math.min(cropRightSource, Math.max(0, sourceWidth - cropLeftSource - 1))
    );
    cropTopSource = Math.max(0, Math.min(cropTopSource, Math.max(0, sourceHeight - 1)));
    cropBottomSource = Math.max(
      0,
      Math.min(cropBottomSource, Math.max(0, sourceHeight - cropTopSource - 1))
    );

    exemptHiddenApis();
    // Construction only reflects hidden APIs and builds immutable capture parameters. It does
    // not acquire pixels; the first capture() remains below the exact activation-token gate.
    SurfaceCapture capture = new SecureScreenCapture(sourceWidth, sourceHeight);
    AtomicBoolean syncFrameRequested = new AtomicBoolean(true);
    AtomicBoolean captureActivated = new AtomicBoolean(!awaitActivation);
    CountDownLatch activationLatch = new CountDownLatch(awaitActivation ? 1 : 0);
    AtomicReference<ControlCodeVisualProbeRequest> controlCodeVisualProbeRequest =
      new AtomicReference<>(ControlCodeVisualProbeRequest.idle());
    Object frameWaitLock = new Object();
    AtomicLong captureAttemptSequence = new AtomicLong(0L);
    TicketCaptureCadenceScheduler cadenceScheduler = new TicketCaptureCadenceScheduler(
      SystemClock.elapsedRealtime()
    );
    if (frames <= 0) {
      cadenceScheduler.parkOrdinaryCapture();
      System.err.println("CAPTURE_POLICY demand_gated=true");
    }
    TicketNewestFrameHandoff<PendingCapture> pendingFrames = new TicketNewestFrameHandoff<>();
    EncodingOwner encoding = new EncodingOwner(pendingFrames, width, height, bitrate, frames,
      codecGeneration, cadenceScheduler);
    encoding.start();
    Thread commandThread = null;
    try {
      commandThread = startCommandReader(
        syncFrameRequested,
        controlCodeVisualProbeRequest,
        captureActivated,
        activationLatch,
        activationToken,
        frameWaitLock,
        cadenceScheduler,
        encoding
      );

      // This is the hard privacy and admission boundary. Before activation the helper may load
      // classes and configure MediaCodec, but it must not acquire a screen capture, post the
      // input Surface, dequeue codec output, or write anything to stdout.
      if (awaitActivation && !activationLatch.await(ARM_ACTIVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        System.err.println("capture_activation_timeout");
        return;
      }
      if (!captureActivated.get()) {
        return;
      }

      // The native Android display composition can carry a one-pixel colored ring at the
      // physical left, right, and bottom edges. The configured rectangle also leaves two source
      // pixels of sampling guard on the live-proven left edge and one on the right and bottom.
      // Use the same rectangle for encoding, visibility, and every Ticket visual
      // classifier so proof geometry and visible media always describe the same cropped source.
      Rect sourceCrop = sourceCropRect(
        cropLeftSource,
        cropTopSource,
        cropRightSource,
        cropBottomSource,
        sourceWidth,
        sourceHeight
      );
      int sent = 0;
      int startupVisibilityFrames = 1;
      int blockedOrBlankFrames = 0;
      long lastVisibilityProbeAt = 0L;
      boolean lastVisibilityVisible = true;
      while (frames <= 0 || sent < frames) {
        long started;
        ControlCodeVisualProbeRequest visualProbeRequest;
        boolean controlCodeVisualProbeActive;
        synchronized (frameWaitLock) {
          while (true) {
            started = SystemClock.elapsedRealtime();
            visualProbeRequest = controlCodeVisualProbeRequest.get();
            controlCodeVisualProbeActive = started <= visualProbeRequest.untilMillis.get();
            boolean proofBypass =
              sent == 0 || syncFrameRequested.get() || controlCodeVisualProbeActive;
            long waitMillis = cadenceScheduler.waitMillis(started, proofBypass);
            if (waitMillis == 0L) {
              cadenceScheduler.beginCapture(started, proofBypass);
              break;
            }
            if (waitMillis == TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS) {
              frameWaitLock.wait();
            } else {
              frameWaitLock.wait(waitMillis);
            }
          }
          syncFrameRequested.set(false);
        }
        long captureAttemptId = captureAttemptSequence.incrementAndGet();
        long captureStartUs = monotonicTimeUs();
        // Anchor the minimum interval to the actual capture boundary, including sync-call cost.
        cadenceScheduler.restartPeriodFrom((captureStartUs + 999L) / 1_000L);
        CapturedFrame source = capture.capture();
        long captureCompleteUs = monotonicTimeUs();
        if (source == null) {
          throw new IllegalStateException("ScreenCapture returned no bitmap");
        }
        boolean visible = lastVisibilityVisible;
        boolean handedToEncoder = false;
        try {
          boolean shouldProbeVisibility =
            (sent < startupVisibilityFrames || started - lastVisibilityProbeAt >= VISIBILITY_PROBE_INTERVAL_MILLIS);
          if (shouldProbeVisibility) {
            lastVisibilityProbeAt = started;
            visible = frameLooksVisible(source.bitmap, sourceCrop);
            lastVisibilityVisible = visible;
            if (visible) {
              blockedOrBlankFrames = 0;
              System.err.println("VISIBILITY result=visible");
            } else {
              blockedOrBlankFrames += 1;
              boolean failure = sent >= startupVisibilityFrames && blockedOrBlankFrames >= 4;
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
          // Ticket recognition and codec work read the same immutable capture.
          // Publish media promptly instead of waiting for CPU recognition. The
          // code-generation probe retains its diagnostic-before-media ordering.
          if (!controlCodeVisualProbeActive || visualProbeRequest.ticketAction) {
            source.retain();
            handedToEncoder = true;
            pendingFrames.offer(new PendingCapture(source, sourceCrop, captureAttemptId,
              captureStartUs, captureCompleteUs));
          }
          if (controlCodeVisualProbeActive) {
            VisualProbeClassification visual = classifyControlCodeVisualState(
              source.bitmap,
              sourceCrop,
              visualProbeRequest.cleanup,
              visualProbeRequest.submitLayout,
              visualProbeRequest.ticketAction,
              visualProbeRequest.ticketCurrentOnly
            );
            String state = visual.state;
            String boundsDiagnostic = visual.sliderBounds.isEmpty()
              ? ""
              : " slider_bounds=" + visual.sliderBounds;
            if (!visual.closeBounds.isEmpty()) {
              boundsDiagnostic += " close_bounds=" + visual.closeBounds;
            }
            if (!visual.inputBounds.isEmpty()) {
              boundsDiagnostic += " input_bounds=" + visual.inputBounds;
            }
            if (!visual.submitBounds.isEmpty()) {
              boundsDiagnostic += " submit_bounds=" + visual.submitBounds;
            }
            String extraDiagnostic = visual.extraDiagnostic.isEmpty()
              ? ""
              : " " + visual.extraDiagnostic;
            String visualSignatureDiagnostic = visual.visualSignature.isEmpty() ||
              visual.visualSignatureEpoch.isEmpty()
              ? ""
              : " visual_signature=" + visual.visualSignature +
                " visual_signature_epoch=" + visual.visualSignatureEpoch;
            String methodDiagnostic = visualProbeRequest.ticketAction
              ? " method=ticket_action_visual_probe capture_start_us=" + captureStartUs + extraDiagnostic
              : " method=h264_bitmap_probe capture_start_us=" + captureStartUs + visualSignatureDiagnostic + extraDiagnostic;
            if (state.equals("generated")) {
              if (visualProbeRequest.generated.compareAndSet(false, true)) {
                visualProbeRequest.lastReportMillis.set(started);
                extendUntil(visualProbeRequest.untilMillis, started + CONTROL_CODE_VISUAL_GENERATED_HOLD_MILLIS);
                System.err.println(
                  "CONTROL_CODE_VISUAL result=generated reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                    " probe_id=" + visualProbeRequest.id +
                    methodDiagnostic + boundsDiagnostic
                );
              } else if (started - visualProbeRequest.lastReportMillis.get() >= CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS) {
                visualProbeRequest.lastReportMillis.set(started);
                System.err.println(
                  "CONTROL_CODE_VISUAL result=generated reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                    " probe_id=" + visualProbeRequest.id +
                    methodDiagnostic + boundsDiagnostic
                );
              }
            } else if (started - visualProbeRequest.lastReportMillis.get() >= CONTROL_CODE_VISUAL_REPORT_INTERVAL_MILLIS) {
              visualProbeRequest.lastReportMillis.set(started);
              System.err.println(
                "CONTROL_CODE_VISUAL result=" + state +
                  " reason=" + safeDiagnosticValue(visualProbeRequest.reason) +
                  " probe_id=" + visualProbeRequest.id +
                  methodDiagnostic + boundsDiagnostic
              );
              if (visualProbeRequest.ticketAction) {
                // Ticket action probes are explicit, one-frame observations. Keeping the request
                // active would rerun the date recognizer on every later fixed-cadence frame even though the
                // action executor can consume only one result for this probe id.
                visualProbeRequest.untilMillis.set(0L);
              }
            }
          }
          if (!(controlCodeVisualProbeActive && visualProbeRequest.ticketAction)) {
            // Reuse this ordinary capture, before any codec draw/drain. This is
            // current-view recognition only; date/list search stays command-owned.
            VisualProbeClassification current = classifyControlCodeVisualState(
              source.bitmap, sourceCrop, false, false, true, true
            );
            System.err.println("PHONE_CONTROL_OBSERVATION method=ticket_action_visual_probe" +
              " capture_start_us=" + captureStartUs + " result=" + current.state +
              " " + current.extraDiagnostic);
          }
          if (!handedToEncoder) {
            source.retain();
            handedToEncoder = true;
            pendingFrames.offer(new PendingCapture(source, sourceCrop, captureAttemptId,
              captureStartUs, captureCompleteUs));
          }
        } finally {
          // Release the recognition reader; the encoder retains its independent
          // reference until drain, replacement, shutdown, or codec failure cleanup.
          source.close();
        }
        // Primer posts are internal encoder work for one captured source picture. Keep --frames
        // probe semantics and the public frame count tied to captured pictures, not primer posts.
        sent += 1;

      }
      pendingFrames.finish();
      encoding.join(8_000L);
    } finally {
      if (commandThread != null) {
        commandThread.interrupt();
      }
      pendingFrames.close();
      encoding.interrupt();
    }
  }


  private static final class PendingCapture implements AutoCloseable {
    final CapturedFrame source;
    final Rect sourceCrop;
    final long captureAttemptId, captureStartUs, captureCompleteUs;
    PendingCapture(CapturedFrame source, Rect sourceCrop, long captureAttemptId,
        long captureStartUs, long captureCompleteUs) {
      this.source = source; this.sourceCrop = sourceCrop;
      this.captureAttemptId = captureAttemptId; this.captureStartUs = captureStartUs;
      this.captureCompleteUs = captureCompleteUs;
    }
    @Override public void close() { source.close(); }
  }

  /** Codec setup, GPU lifetime, drain and output have exactly one separate owner. */
  private static final class EncodingOwner extends Thread {
    final AtomicBoolean restartRequested = new AtomicBoolean(false);
    final FrameOutput output = new FrameOutput();
    final TicketNewestFrameHandoff<PendingCapture> pendingFrames;
    final int width, height, bitrate, frames;
    final int encoderFps = TicketCaptureCadenceScheduler.FIXED_FPS;
    long codecGeneration;
    final TicketCaptureCadenceScheduler cadenceScheduler;
    EncodingOwner(TicketNewestFrameHandoff<PendingCapture> pendingFrames, int width, int height,
        int bitrate, int frames, long codecGeneration,
        TicketCaptureCadenceScheduler cadenceScheduler) {
      super("ticket-encoder");
      setDaemon(true);
      this.pendingFrames = pendingFrames; this.width = width; this.height = height;
      this.bitrate = bitrate; this.frames = frames; this.codecGeneration = codecGeneration;
      this.cadenceScheduler = cadenceScheduler;
    }
    @Override public void run() {
      while (!isInterrupted()) {
        try {
          encodeSession();
          return;
        } catch (InterruptedException stopped) {
          interrupt();
          return;
        } catch (Exception failure) {
          // Encoder failure leaves capture and observation alive. Do not log private
          // exception text; failed output and unavailable capture are distinct signals.
          System.err.println("ENCODER_SESSION state=failed");
          if (pendingFrames.isClosed()) return;
          codecGeneration = Math.max(codecGeneration + 1L, SystemClock.elapsedRealtime());
          try { Thread.sleep(500L); } catch (InterruptedException stopped) { interrupt(); return; }
        }
      }
    }
    private void encodeSession() throws Exception {
      MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
      Surface inputSurface = null;
      TicketH264EncoderOutputAssembler outputAssembler = new TicketH264EncoderOutputAssembler();
      TicketCodecInputLedger codecInputLedger = new TicketCodecInputLedger();
      TicketEncoderStartupPrimer startupPrimer = new TicketEncoderStartupPrimer(frames);
      Rect destination = new Rect(0, 0, width, height);
      Paint paint = hardwareColorCorrectionPaint();
      int sent = 0;
      PendingCapture failedInput = null;
      try {
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, encoderFps);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      format.setInteger(MediaFormat.KEY_LATENCY, 0);
      // Operating rate reserves codec processing capacity, not capture/output cadence.
      // A 1 fps resource hint lets this vendor encoder spend most of a second on
      // each picture. Process promptly; the capture scheduler and output pacer
      // still enforce the single-frame-per-second contract.
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, 30);
      if (supportsCbrBitrateMode(encoder)) {
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
      }
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
      format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
      format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
      format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
      format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      inputSurface = encoder.createInputSurface();
      encoder.start();
      System.err.println(
        "ENCODER_CONFIG name=" + safeDiagnosticValue(encoder.getName()) +
          " configured_profile=baseline configured_level=4 configured_bitrate_mode=" +
          (supportsCbrBitrateMode(encoder) ? "cbr" : "encoder_default") +
          " bitrate=" + bitrate +
          " configured_fps=" + encoderFps +
          " keyframe_interval_frames=1 frame_dependency_mode=all_intra" +
          " helper_frame_record=thf1 max_payload_bytes=" + TicketH264FrameRecord.MAX_PAYLOAD_BYTES
      );

        PendingCapture packet;
        while ((packet = pendingFrames.take()) != null) {
          boolean inputResolved = false;
          try {
            if (restartRequested.getAndSet(false)) throw new IllegalStateException("encoder_restart_requested");
            // A stalled encoder resumes with the newest still-current picture.
            if (monotonicTimeUs() - packet.captureStartUs >= 3_000_000L) {
              inputResolved = true;
              continue;
            }
            if (sent > 0) requestSyncFrame(encoder);
          if (sent == 0) {
            runStartupPrimer(
              encoder,
              inputSurface,
              packet.source.bitmap,
              packet.sourceCrop,
              destination,
              paint,
              output,
              outputAssembler,
              startupPrimer,
              codecInputLedger,
              packet.captureAttemptId,
              codecGeneration,
              packet.captureStartUs,
              packet.captureCompleteUs,
              cadenceScheduler
            );
          } else {
            boolean closeStartupPrimerAfterDrain =
              startupPrimer.firstKeyFrameForwarded() && !startupPrimer.finished();
            if (closeStartupPrimerAfterDrain) {
              startupPrimer.beginBoundaryDrain();
            }
            drawBitmap(inputSurface, packet.source.bitmap, packet.sourceCrop, destination, paint);
            long codecInputUs = monotonicTimeUs();
            TicketCodecInputLedger.InputStage inputStage = new TicketCodecInputLedger.InputStage(
              packet.captureAttemptId,
              codecGeneration,
              packet.captureStartUs,
              packet.captureCompleteUs,
              codecInputUs
            );
            codecInputLedger.add(inputStage);
            drainEncoderUntilInputResolved(
                encoder,
                output,
                outputAssembler,
                startupPrimer.finished() ? null : startupPrimer,
                codecInputLedger,
                cadenceScheduler,
                inputStage
            );
            if (
              startupPrimer.fallbackWaitingForFirstKeyFrame() &&
              startupPrimer.firstKeyFrameForwarded() &&
              !startupPrimer.finished()
            ) {
              // drainEncoder consumes every currently available AU before returning. Finish only
              // after a final sweep at the next rebased cadence boundary. A sibling primer output
              // can become ready just after this drain reports TRY_AGAIN.
              output.flush();
            }
            if (
              closeStartupPrimerAfterDrain &&
              startupPrimer.boundaryAccessUnitForwarded() &&
              !startupPrimer.finished()
            ) {
              // A delayed vendor encoder can release old primer pictures only after this steady
              // Surface post. Keep the newest IDR from the whole resulting drain, expose exactly
              // that one boundary picture, then open the following steady period.
              startupPrimer.finish();
            }
          }
          output.flush();

            inputResolved = true;
            sent++;
          } finally {
            // GPU input retains the bitmap through drain; capture never closes an
            // in-flight picture, even if its pending successor is replaced.
            if (inputResolved) packet.close();
            else failedInput = packet;
          }
        }
      encoder.signalEndOfInputStream();
      drainEncoder(
        encoder,
        output,
        outputAssembler,
        true,
        100_000L,
        startupPrimer.finished() ? null : startupPrimer,
        codecInputLedger,
        cadenceScheduler,
        monotonicTimeUs() + ENCODER_INPUT_TIMEOUT_US
      );
      if (!startupPrimer.finished()) {
        startupPrimer.finish();
      }
      outputAssembler.reset();
      codecInputLedger.clear();
      output.flush();
      } finally {
        if (inputSurface != null) inputSurface.release();
        runQuietly(encoder::stop);
        encoder.release();
        // A failed draw/drain retains its source until the codec has released it.
        if (failedInput != null) failedInput.close();
      }
    }
  }

  private static Thread startCommandReader(
    AtomicBoolean syncFrameRequested,
    AtomicReference<ControlCodeVisualProbeRequest> controlCodeVisualProbeRequest,
    AtomicBoolean captureActivated,
    CountDownLatch activationLatch,
    long activationToken,
    Object frameWaitLock,
    TicketCaptureCadenceScheduler cadenceScheduler,
    EncodingOwner encoding
  ) {
    Thread thread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String cmd = line.trim();
          if (cmd.equals("restart_encoder")) {
            encoding.restartRequested.set(true);
            requestNextSyncFrame(syncFrameRequested, frameWaitLock);
            continue;
          }
          if (cmd.equals("activate:" + activationToken) && activationToken > 0L) {
            if (captureActivated.compareAndSet(false, true)) {
              activationLatch.countDown();
            }
          } else if (cmd.equals("keyframe")) {
            requestNextSyncFrame(syncFrameRequested, frameWaitLock);
          } else if (cmd.startsWith("capture_demand:")) {
            long validUntilMillis = parseLong(
              cmd.substring("capture_demand:".length()),
              0L
            );
            synchronized (frameWaitLock) {
              if (
                cadenceScheduler.enableDemandGateAndLatchOrdinaryCapture(
                  validUntilMillis,
                  SystemClock.elapsedRealtime()
                )
              ) {
                frameWaitLock.notifyAll();
              }
            }
          } else if (
            cmd.startsWith("control_code_visual_probe:") ||
            cmd.startsWith("control_code_submit_visual_probe:") ||
            cmd.startsWith("control_code_cleanup_visual_probe:") ||
            cmd.startsWith("ticket_action_visual_probe:") ||
            cmd.startsWith("ticket_current_visual_probe:")
          ) {
            int separator = cmd.lastIndexOf(':');
            long probeId = parseLong(cmd.substring(separator + 1), 0L);
            if (probeId <= 0L) {
              continue;
            }
            boolean cleanup = cmd.startsWith("control_code_cleanup_visual_probe:");
            boolean submitLayout = cmd.startsWith("control_code_submit_visual_probe:");
            boolean ticketCurrentOnly = cmd.startsWith("ticket_current_visual_probe:");
            boolean ticketAction = cmd.startsWith("ticket_action_visual_probe:") || ticketCurrentOnly;
            controlCodeVisualProbeRequest.set(new ControlCodeVisualProbeRequest(
              probeId,
              ticketAction ? "ticket_action" : (cleanup ? "control_code_cleanup" : (submitLayout ? "control_code_submit_layout" : "control_code_after_ok")),
              cleanup,
              submitLayout,
              ticketAction,
              ticketCurrentOnly,
              SystemClock.elapsedRealtime() + CONTROL_CODE_VISUAL_PROBE_MILLIS
            ));
            requestNextSyncFrame(syncFrameRequested, frameWaitLock);
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
    final boolean ticketAction;
    final boolean ticketCurrentOnly;
    final AtomicLong untilMillis;
    final AtomicLong lastReportMillis = new AtomicLong(0L);
    final AtomicBoolean generated = new AtomicBoolean(false);

    ControlCodeVisualProbeRequest(
      long id,
      String reason,
      boolean cleanup,
      boolean submitLayout,
      boolean ticketAction,
      boolean ticketCurrentOnly,
      long untilMillis
    ) {
      this.id = id;
      this.reason = reason;
      this.cleanup = cleanup;
      this.submitLayout = submitLayout;
      this.ticketAction = ticketAction;
      this.ticketCurrentOnly = ticketCurrentOnly;
      this.untilMillis = new AtomicLong(untilMillis);
    }

    static ControlCodeVisualProbeRequest idle() {
      return new ControlCodeVisualProbeRequest(0L, "idle", false, false, false, false, 0L);
    }
  }

  private static void requestNextSyncFrame(
    AtomicBoolean syncFrameRequested,
    Object frameWaitLock
  ) {
    synchronized (frameWaitLock) {
      syncFrameRequested.set(true);
      // Wake a parked helper for the next legal cadence boundary. A proof request bypasses
      // viewer demand only; it never moves the one-second deadline forward.
      frameWaitLock.notifyAll();
    }
  }

  private static void runStartupPrimer(
    MediaCodec encoder,
    Surface inputSurface,
    Bitmap source,
    Rect sourceCrop,
    Rect destination,
    Paint paint,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    TicketEncoderStartupPrimer primer,
    TicketCodecInputLedger codecInputLedger,
    long captureAttemptId,
    long codecGeneration,
    long captureStartUs,
    long captureCompleteUs,
    TicketCaptureCadenceScheduler cadenceScheduler
  ) throws Exception {

    while (primer.canPostAnotherInput() && !primer.firstKeyFrameForwarded()) {
      long nowMillis = SystemClock.elapsedRealtime();
      long waitMillis = primer.millisUntilNextInput(nowMillis);
      if (waitMillis > 0L) {
        drainEncoder(
          encoder,
          output,
          outputAssembler,
          false,
          Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, waitMillis) * 1_000L,
          primer,
          codecInputLedger,
          cadenceScheduler,
          monotonicTimeUs() + ENCODER_INPUT_TIMEOUT_US
        );
        if (primer.firstKeyFrameForwarded()) {
          output.flush();
        }
        continue;
      }

      requestSyncFrame(encoder);
      drawBitmap(inputSurface, source, sourceCrop, destination, paint);
      long codecInputUs = monotonicTimeUs();
      long inputPostedAt = codecInputUs / 1_000L;
      codecInputLedger.add(new TicketCodecInputLedger.InputStage(
        captureAttemptId,
        codecGeneration,
        captureStartUs,
        captureCompleteUs,
        codecInputUs
      ));
      primer.noteInputPosted(inputPostedAt);
      drainEncoder(
        encoder,
        output,
        outputAssembler,
        false,
        STARTUP_PRIMER_DRAIN_POLL_MILLIS * 1_000L,
        primer,
        codecInputLedger,
        cadenceScheduler,
        monotonicTimeUs() + ENCODER_INPUT_TIMEOUT_US
      );
      if (primer.firstKeyFrameForwarded()) {
        output.flush();
      }
    }

    if (!primer.firstKeyFrameForwarded() && primer.inputPosts() >= primer.inputLimit()) {
      long finalDrainDeadline = primer.lastInputAtMillis() + STARTUP_PRIMER_FINAL_DRAIN_MILLIS;
      while (!primer.firstKeyFrameForwarded() && SystemClock.elapsedRealtime() < finalDrainDeadline) {
        long remainingMillis = Math.max(1L, finalDrainDeadline - SystemClock.elapsedRealtime());
        drainEncoder(
          encoder,
          output,
          outputAssembler,
          false,
          Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, remainingMillis) * 1_000L,
          primer,
          codecInputLedger,
          cadenceScheduler,
          monotonicTimeUs() + ENCODER_INPUT_TIMEOUT_US
        );
      }
      if (primer.firstKeyFrameForwarded()) {
        output.flush();
      }
    }

    if (primer.firstKeyFrameForwarded()) {
      // A successful primer may have already queued more complete pictures. Consume those now,
      // then keep the gate active until the first steady-cadence boundary for one final sweep.
      long settleDeadline = SystemClock.elapsedRealtime() + STARTUP_PRIMER_SETTLE_MILLIS;
      while (
        primer.mediaOutputs() < primer.inputPosts() &&
        SystemClock.elapsedRealtime() < settleDeadline
      ) {
        long remainingMillis = Math.max(1L, settleDeadline - SystemClock.elapsedRealtime());
        drainEncoder(
          encoder,
          output,
          outputAssembler,
          false,
          Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, remainingMillis) * 1_000L,
          primer,
          codecInputLedger,
          cadenceScheduler,
          monotonicTimeUs() + ENCODER_INPUT_TIMEOUT_US
        );
      }
      output.flush();
    } else {
      // Do not open the gate here. The vendor may have delayed rather than dropped these Surface
      // posts. The first ordinary one-FPS drain will forward one complete keyframe, suppress every
      // sibling it dequeues, and then finish the gate without relying on a residual frame count.
      primer.beginFallbackWait();
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
      // Pixel-side output filtering prevents a rejected request from leaking a dependent frame.
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
    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    paint.setFilterBitmap(true);
    paint.setDither(true);
    paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
      0f, 0f, HIGH_BRIGHTNESS_SDR_RED_GAIN, 0f, 0f,
      0f, HIGH_BRIGHTNESS_SDR_GREEN_GAIN, 0f, 0f, 0f,
      HIGH_BRIGHTNESS_SDR_BLUE_GAIN, 0f, 0f, 0f, 0f,
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
      drawScaledCrop(canvas, source, sourceCrop, destination, paint);
    } finally {
      if (canvas != null) {
        surface.unlockCanvasAndPost(canvas);
      }
    }
  }

  static void drawScaledCrop(
    Canvas canvas,
    Bitmap source,
    Rect sourceCrop,
    Rect destination,
    Paint paint
  ) {
    canvas.drawBitmap(source, sourceCrop, destination, paint);
  }

  static Rect sourceCropRect(
    int cropLeftSource,
    int cropTopSource,
    int cropRightSource,
    int cropBottomSource,
    int sourceWidth,
    int sourceHeight
  ) {
    return new Rect(
      cropLeftSource,
      cropTopSource,
      sourceWidth - cropRightSource,
      sourceHeight - cropBottomSource
    );
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
      int[] pixels = new int[VISIBILITY_SAMPLE_WIDTH * VISIBILITY_SAMPLE_HEIGHT];
      probe.getPixels(
        pixels,
        0,
        VISIBILITY_SAMPLE_WIDTH,
        0,
        0,
        VISIBILITY_SAMPLE_WIDTH,
        VISIBILITY_SAMPLE_HEIGHT
      );
      return TicketCaptureVisibilityClassifier.looksVisible(pixels);
    } finally {
      probe.recycle();
      if (copiedSource && readableSource != null) {
        readableSource.recycle();
      }
    }
  }

  private static VisualProbeClassification classifyControlCodeVisualState(
    Bitmap source,
    Rect sourceCrop,
    boolean cleanupProbe,
    boolean submitLayoutProbe,
    boolean ticketActionProbe,
    boolean ticketCurrentOnly
  ) {
    Bitmap readableSource = source;
    boolean copiedSource = false;
    int probeWidth = ticketActionProbe
      ? TicketVisualActionClassifier.PROBE_WIDTH
      : (submitLayoutProbe || cleanupProbe)
      ? TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_WIDTH
      : TicketControlCodeVisualClassifier.SAMPLE_WIDTH;
    int probeHeight = ticketActionProbe
      ? TicketVisualActionClassifier.PROBE_HEIGHT
      : (submitLayoutProbe || cleanupProbe)
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
      // The action detector intentionally uses a larger one-shot probe. Bilinear shrinking keeps
      // the ViVi card's thin date strokes represented while the bounded classifier still fails
      // closed on ambiguous glyphs. The continuous video path is unchanged.
      paint.setFilterBitmap(ticketActionProbe);
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
        return new VisualProbeClassification(
          TicketControlCodeVisualClassifier.classifySubmitLayout(pixels),
          "",
          "",
          TicketControlCodeVisualClassifier.submitInputBounds(pixels),
          TicketControlCodeVisualClassifier.submitButtonBounds(pixels),
          "",
          "",
          ""
        );
      }
      if (ticketActionProbe) {
        TicketVisualActionClassifier.Result result = ticketCurrentOnly
          ? TicketVisualActionClassifier.classifyCurrent(pixels)
          : TicketVisualActionClassifier.classify(pixels);
        String selectedBottomTab =
          TicketVisualActionClassifier.selectedBottomNavigationTab(pixels);
        String wire = result.wire() +
          (selectedBottomTab.isEmpty() ? "" : " bottom_tab=" + selectedBottomTab);
        return new VisualProbeClassification(
          result.state,
          "",
          "",
          "",
          "",
          "",
          "",
          wire.substring(("state=" + result.state).length()).trim()
        );
      }
      String state;
      if (cleanupProbe) {
        state = TicketControlCodeVisualClassifier.classifyForCleanupHighResolution(pixels);
      } else {
        state = TicketControlCodeVisualClassifier.classify(pixels);
      }
      String sliderBounds = "";
      String closeBounds = cleanupProbe
        ? TicketControlCodeVisualClassifier.generatedResultCloseBoundsHighResolution(pixels)
        : "";
      String visualSignature = cleanupProbe
        ? TicketControlCodeVisualClassifier.ticketCodeVisualSignatureHighResolution(pixels)
        : TicketControlCodeVisualClassifier.ticketCodeVisualSignature(pixels);
      String visualSignatureEpoch = visualSignature.isEmpty()
        ? ""
        : TicketControlCodeVisualClassifier.ticketCodeVisualSignatureEpoch();
      return new VisualProbeClassification(
        state,
        sliderBounds,
        closeBounds,
        "",
        "",
        visualSignature,
        visualSignatureEpoch,
        ""
      );
    } finally {
      probe.recycle();
      if (copiedSource && readableSource != null) {
        readableSource.recycle();
      }
    }
  }

  private static final class VisualProbeClassification {
    final String state;
    final String sliderBounds;
    final String closeBounds;
    final String inputBounds;
    final String submitBounds;
    final String visualSignature;
    final String visualSignatureEpoch;
    final String extraDiagnostic;

    VisualProbeClassification(
      String state,
      String sliderBounds,
      String closeBounds,
      String inputBounds,
      String submitBounds,
      String visualSignature,
      String visualSignatureEpoch,
      String extraDiagnostic
    ) {
      this.state = state;
      this.sliderBounds = sliderBounds == null ? "" : sliderBounds;
      this.closeBounds = closeBounds == null ? "" : closeBounds;
      this.inputBounds = inputBounds == null ? "" : inputBounds;
      this.submitBounds = submitBounds == null ? "" : submitBounds;
      this.visualSignature = visualSignature == null ? "" : visualSignature;
      this.visualSignatureEpoch = visualSignatureEpoch == null ? "" : visualSignatureEpoch;
      this.extraDiagnostic = extraDiagnostic == null ? "" : extraDiagnostic;
    }
  }

  private static String safeDiagnosticValue(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
  }

  private static void drainEncoder(
    MediaCodec encoder,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    boolean endOfStream,
    long timeoutUs,
    TicketEncoderStartupPrimer startupPrimer,
    TicketCodecInputLedger codecInputLedger,
    TicketCaptureCadenceScheduler cadenceScheduler,
    long deadlineUs
  )
    throws Exception {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    while (true) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (monotonicTimeUs() >= deadlineUs) throw new IllegalStateException("encoder_drain_deadline_exceeded");
      int index = encoder.dequeueOutputBuffer(info, timeoutUs);
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (endOfStream) {
          continue;
        }
        // A short empty poll can occur between queued primer siblings and the current picture.
        // Finalize the boundary only after every posted input has produced its complete AU.
        if (codecInputLedger.size() == 0) {
          forwardSelectedBoundaryAccessUnit(output, startupPrimer, cadenceScheduler);
        }
        return;
      }
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
        continue;
      }
      if (index < 0) {
        continue;
      }
      boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      try {
        ByteBuffer buffer = encoder.getOutputBuffer(index);
        byte[] data = new byte[0];
        if (buffer != null && info.size > 0) {
          buffer.position(info.offset);
          buffer.limit(info.offset + info.size);
          data = new byte[info.size];
          buffer.get(data);
        }
        boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        boolean partialFrame = (info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0;
        if (eos && data.length == 0) {
          // EOS with no bytes must not turn an unfinished partial access unit into a frame.
          outputAssembler.reset();
        } else {
          TicketH264EncoderOutputAssembler.EmittedAccessUnit emitted = outputAssembler.accept(
            data,
            partialFrame,
            codecConfig,
            keyFrame
          );
          if (outputAssembler.consumeOverflowed()) {
            throw new IllegalStateException("encoded access unit exceeds 2 MiB");
          }
          if (emitted == null && !partialFrame && !codecConfig && data.length > 0) {
            throw new IllegalStateException("completed media access unit could not be framed");
          }
          TicketH264FrameRecord frame = null;
          if (emitted != null && emitted.containsVcl) {
            TicketCodecInputLedger.InputStage inputStage = codecInputLedger.take();
            frame = new TicketH264FrameRecord(
              emitted.idrKeyFrame,
              inputStage.captureAttemptId,
              inputStage.codecGeneration,
              inputStage.captureStartUs,
              inputStage.captureCompleteUs,
              inputStage.codecInputUs,
              monotonicTimeUs(),
              0L,
              emitted.payload
            );
          }
          TicketEncoderStartupPrimer.OutputDisposition disposition =
            TicketEncoderStartupPrimer.OutputDisposition.FORWARD;
          if (frame != null && startupPrimer != null) {
            disposition = startupPrimer.classifyCompleteAccessUnit(
                frame,
                emitted.containsVcl,
                emitted.idrKeyFrame,
                SystemClock.elapsedRealtime()
              );
          }
          if (
            frame != null &&
            disposition == TicketEncoderStartupPrimer.OutputDisposition.FORWARD
          ) {
            writeFrameRecord(output, frame, cadenceScheduler);
          }
          if (eos) {
            outputAssembler.reset();
          }
        }
      } finally {
        // Every dequeued buffer must be returned even if assembly, gating, or pipe I/O fails.
        encoder.releaseOutputBuffer(index, false);
      }
      if (eos) {
        forwardSelectedBoundaryAccessUnit(output, startupPrimer, cadenceScheduler);
        return;
      }
      if (!endOfStream) {
        timeoutUs = 0L;
      }
    }
  }

  private static void drainEncoderUntilInputResolved(
    MediaCodec encoder,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    TicketEncoderStartupPrimer startupPrimer,
    TicketCodecInputLedger codecInputLedger,
    TicketCaptureCadenceScheduler cadenceScheduler,
    TicketCodecInputLedger.InputStage inputStage
  ) throws Exception {
    // Keep this input as the sole outstanding steady-state post. A stuck codec must
    // reach EncodingOwner's cleanup/restart path; waiting for another input first
    // would also prevent an externally requested encoder restart from taking effect.
    while (codecInputLedger.contains(inputStage)) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (monotonicTimeUs() - inputStage.codecInputUs > ENCODER_INPUT_TIMEOUT_US) {
        throw new IllegalStateException("encoder_input_deadline_exceeded");
      }
      drainEncoder(
        encoder,
        output,
        outputAssembler,
        false,
        STARTUP_PRIMER_DRAIN_POLL_MILLIS * 1_000L,
        startupPrimer,
        codecInputLedger,
        cadenceScheduler,
        inputStage.codecInputUs + ENCODER_INPUT_TIMEOUT_US
      );
    }
  }

  static void forwardSelectedBoundaryAccessUnit(
    OutputStream output,
    TicketEncoderStartupPrimer startupPrimer,
    TicketCaptureCadenceScheduler cadenceScheduler
  ) throws Exception {
    if (startupPrimer == null || !startupPrimer.boundaryDrainActive()) {
      return;
    }
    TicketH264FrameRecord selected = startupPrimer.completeBoundaryDrain();
    if (selected == null) {
      return;
    }
    writeFrameRecord(output, selected, cadenceScheduler);
    startupPrimer.noteBoundaryAccessUnitForwarded();
  }

  static void writeFrameRecord(
    OutputStream output,
    TicketH264FrameRecord frame,
    TicketCaptureCadenceScheduler cadenceScheduler
  ) throws Exception {
    if (output instanceof FrameOutput) ((FrameOutput) output).awaitNextPicture();
    TicketH264FrameRecord.write(output, frame.withRecordEmissionUs(monotonicTimeUs()));
    output.flush();
    if (output instanceof FrameOutput) ((FrameOutput) output).lastPictureAtMillis = SystemClock.elapsedRealtime();
    cadenceScheduler.notePictureEmitted(SystemClock.elapsedRealtime());
  }

  /** Pace only media writes, including recovery bursts; capture never waits here. */
  private static final class FrameOutput extends BufferedOutputStream {
    long lastPictureAtMillis;
    FrameOutput() { super(System.out, 256 * 1024); }
    void awaitNextPicture() throws InterruptedException {
      long remaining = lastPictureAtMillis + TicketCaptureCadenceScheduler.INTERVAL_MILLIS - SystemClock.elapsedRealtime();
      if (lastPictureAtMillis > 0L && remaining > 0L) Thread.sleep(remaining);
    }
  }

  private static long monotonicTimeUs() {
    return SystemClock.elapsedRealtimeNanos() / 1_000L;
  }

  private static int intArg(String[] args, String name, int fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (name.equals(args[i])) {
        return Integer.parseInt(args[i + 1]);
      }
    }
    return fallback;
  }

  private static long longArg(String[] args, String name, long fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (name.equals(args[i])) {
        return parseLong(args[i + 1], fallback);
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
    private final TicketSharedFrameLifetime lifetime = new TicketSharedFrameLifetime(this::releaseResources);

    CapturedFrame(Bitmap bitmap, Object result, HardwareBuffer hardwareBuffer) {
      this.bitmap = bitmap;
      this.result = result;
      this.hardwareBuffer = hardwareBuffer;
    }

    void retain() { lifetime.retain(); }

    @Override public void close() { lifetime.close(); }

    private void releaseResources() {
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
