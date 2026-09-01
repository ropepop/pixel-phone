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
  // The public viewer is an SDR canvas. Keep the encoder metadata truthful and apply only a
  // conservative display-referred lift here, after correcting the capture surface's R/B order.
  // No offset is used so black UI surfaces remain black instead of turning gray.
  private static final float HIGH_BRIGHTNESS_SDR_RED_GAIN = 1.08f;
  private static final float HIGH_BRIGHTNESS_SDR_GREEN_GAIN = 1.05f;
  private static final float HIGH_BRIGHTNESS_SDR_BLUE_GAIN = 1.03f;
  private static final String PNG_BASE64_BEGIN = "PNG_BASE64_BEGIN";
  private static final String PNG_BASE64_END = "PNG_BASE64_END";

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
    boolean pngBase64 = hasFlag(args, "--png-base64");
    boolean awaitActivation = hasFlag(args, "--await-activation");
    long activationToken = longArg(args, "--activation-token", 0L);
    long startupRequestedAtMillis = longArg(
      args,
      "--startup-requested-at-millis",
      SystemClock.elapsedRealtime()
    );
    System.err.println(
      "HELPER_STARTUP phase=main request_elapsed_ms=" +
        Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
    );
    if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
      throw new IllegalArgumentException("source and target dimensions are required");
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
    if (pngBase64) {
      captureSecurePngBase64(
        sourceWidth,
        sourceHeight,
        width,
        height,
        cropLeftSource,
        cropTopSource,
        cropRightSource,
        cropBottomSource
      );
      return;
    }

    // Construction only reflects hidden APIs and builds immutable capture parameters. It does
    // not acquire pixels; the first capture() remains below the exact activation-token gate.
    SurfaceCapture capture = new SecureScreenCapture(sourceWidth, sourceHeight);
    MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
    Surface inputSurface = null;
    OutputStream output = new BufferedOutputStream(System.out, 256 * 1024);
    AtomicBoolean syncFrameRequested = new AtomicBoolean(true);
    AtomicBoolean captureActivated = new AtomicBoolean(!awaitActivation);
    CountDownLatch activationLatch = new CountDownLatch(awaitActivation ? 1 : 0);
    AtomicReference<ControlCodeVisualProbeRequest> controlCodeVisualProbeRequest =
      new AtomicReference<>(ControlCodeVisualProbeRequest.idle());
    Object frameWaitLock = new Object();
    TicketH264EncoderOutputAssembler outputAssembler = new TicketH264EncoderOutputAssembler();
    TicketCaptureCadenceScheduler cadenceScheduler = new TicketCaptureCadenceScheduler(
      SystemClock.elapsedRealtime()
    );
    TicketEncoderOutputLiveness outputLiveness = new TicketEncoderOutputLiveness();
    Thread commandThread = null;
    try {
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, encoderFps);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      format.setInteger(MediaFormat.KEY_LATENCY, 0);
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, encoderFps);
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
        "HELPER_STARTUP phase=encoder_started request_elapsed_ms=" +
          Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
      );
      System.err.println(
        "HELPER_STARTUP phase=codec_armed request_elapsed_ms=" +
          Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis) +
          " activation_required=" + awaitActivation
      );
      System.err.println(
        "ENCODER_CONFIG name=" + safeDiagnosticValue(encoder.getName()) +
          " configured_profile=baseline configured_level=4 configured_bitrate_mode=" +
          (supportsCbrBitrateMode(encoder) ? "cbr" : "encoder_default") +
          " bitrate=" + bitrate +
          " configured_fps=" + encoderFps +
          " keyframe_interval_frames=1 frame_dependency_mode=all_intra"
      );
      commandThread = startCommandReader(
        syncFrameRequested,
        controlCodeVisualProbeRequest,
        captureActivated,
        activationLatch,
        activationToken,
        startupRequestedAtMillis,
        frameWaitLock,
        cadenceScheduler
      );

      // This is the hard privacy and admission boundary. Before activation the helper may load
      // classes and configure MediaCodec, but it must not acquire a screen capture, post the
      // input Surface, dequeue codec output, or write anything to stdout.
      if (awaitActivation && !activationLatch.await(ARM_ACTIVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        System.err.println(
          "HELPER_STARTUP phase=activation_timeout request_elapsed_ms=" +
            Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
        );
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
      Rect destination = new Rect(0, 0, width, height);
      Paint paint = hardwareColorCorrectionPaint();
      int sent = 0;
      TicketEncoderStartupPrimer startupPrimer = new TicketEncoderStartupPrimer(frames);
      int startupVisibilityFrames = 1;
      int blockedOrBlankFrames = 0;
      long lastMetricAt = 0L;
      long lastVisibilityProbeAt = 0L;
      boolean lastVisibilityVisible = true;
      while (frames <= 0 || sent < frames) {
        long started = SystemClock.elapsedRealtime();
        ControlCodeVisualProbeRequest visualProbeRequest = controlCodeVisualProbeRequest.get();
        boolean controlCodeVisualProbeActive = started <= visualProbeRequest.untilMillis.get();
        long waitMillis = cadenceScheduler.waitMillis(started);
        if (waitMillis > 0L) {
          waitForNextFrame(frameWaitLock, cadenceScheduler, waitMillis);
          continue;
        }
        TicketCaptureCadenceScheduler.CaptureDecision cadenceDecision;
        boolean explicitSyncFrame;
        synchronized (frameWaitLock) {
          cadenceDecision = cadenceScheduler.beginCapture(started);
          explicitSyncFrame = syncFrameRequested.getAndSet(false);
        }
        long frameIntervalMillis = cadenceScheduler.intervalMillis();
        // Ask explicitly on every input in addition to the all-intra MediaFormat contract.
        if (sent > 0) {
          requestSyncFrame(encoder);
        }
        long captureStarted = SystemClock.elapsedRealtime();
        CapturedFrame source = capture.capture();
        long captureFinished = SystemClock.elapsedRealtime();
        if (sent == 0) {
          System.err.println(
            "HELPER_STARTUP phase=first_capture_done request_elapsed_ms=" +
              Math.max(0L, captureFinished - startupRequestedAtMillis)
          );
        }
        if (source == null) {
          throw new IllegalStateException("ScreenCapture returned no bitmap");
        }
        boolean visible = lastVisibilityVisible;
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
            if (sent == 0) {
              System.err.println(
                "HELPER_STARTUP phase=visibility_done request_elapsed_ms=" +
                  Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
              );
            }
          }
          if (controlCodeVisualProbeActive) {
            VisualProbeClassification visual = classifyControlCodeVisualState(
              source.bitmap,
              sourceCrop,
              visualProbeRequest.cleanup,
              visualProbeRequest.submitLayout,
              "ticket_detail".equals(visualProbeRequest.reason) ||
                visualProbeRequest.activatedTicket,
              visualProbeRequest.activatedTicket,
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
              ? " method=ticket_action_visual_probe" + extraDiagnostic
              : " method=h264_bitmap_probe" + visualSignatureDiagnostic + extraDiagnostic;
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
            if (sent == 0) {
              System.err.println(
                "HELPER_STARTUP phase=visual_probe_done request_elapsed_ms=" +
                  Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
              );
            }
          }
          StartupPrimerRun encoderRun;
          encodeStarted = SystemClock.elapsedRealtime();
          if (sent == 0) {
            encoderRun = runStartupPrimer(
              encoder,
              inputSurface,
              source.bitmap,
              sourceCrop,
              destination,
              paint,
              output,
              outputAssembler,
              startupPrimer
            );
            if (startupPrimer.inputPosts() > 0) {
              System.err.println(
                "HELPER_STARTUP phase=first_input request_elapsed_ms=" +
                  Math.max(0L, startupPrimer.firstInputAtMillis() - startupRequestedAtMillis)
              );
            }
            if (startupPrimer.firstKeyFrameForwarded()) {
              System.err.println(
                "HELPER_STARTUP phase=first_keyframe request_elapsed_ms=" +
                  Math.max(0L, startupPrimer.firstKeyFrameAtMillis() - startupRequestedAtMillis)
              );
            }
            drawStarted = 0L;
            drawFinished = encoderRun.drawDurationMillis;
            if (startupPrimer.firstKeyFrameForwarded()) {
              cadenceScheduler.restartPeriodFrom(startupPrimer.firstKeyFrameAtMillis());
            }
          } else {
            boolean closeStartupPrimerAfterDrain =
              startupPrimer.firstKeyFrameForwarded() && !startupPrimer.finished();
            if (closeStartupPrimerAfterDrain) {
              startupPrimer.beginBoundaryDrain();
            }
            drawStarted = SystemClock.elapsedRealtime();
            drawBitmap(inputSurface, source.bitmap, sourceCrop, destination, paint);
            drawFinished = SystemClock.elapsedRealtime();
            long drainTimeoutUs = sent < 3
              ? Math.min(80_000L, Math.max(25_000L, frameIntervalMillis * 1_000L))
              : 10_000L;
            encoderRun = new StartupPrimerRun(
              drawFinished - drawStarted,
              drainEncoder(
                encoder,
                output,
                outputAssembler,
                false,
                drainTimeoutUs,
                startupPrimer.finished() ? null : startupPrimer
              )
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
              cadenceScheduler.restartPeriodFrom(startupPrimer.firstKeyFrameAtMillis());
              System.err.println(
                "ENCODER_STARTUP_PRIMER state=first_keyframe result=fallback_keyframe" +
                  " input_posts=" + startupPrimer.inputPosts() +
                  " media_outputs=" + startupPrimer.mediaOutputs() +
                  " suppressed_outputs=" + startupPrimer.suppressedMediaOutputs() +
                  " first_keyframe_ms=" + startupPrimer.firstKeyFrameLatencyMillis()
              );
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
              logStartupPrimerComplete(
                startupPrimer.fallbackWaitingForFirstKeyFrame() ? "fallback_keyframe" : "keyframe",
                startupPrimer
              );
            }
          }
          TicketEncoderDrainProgress drainProgress = encoderRun.drainProgress;
          boolean steadyEncoderDrain = sent >= 3;
          boolean scheduledPeriodicDrain =
            !cadenceDecision.immediate && !explicitSyncFrame;
          if (
            outputLiveness.noteDrain(
              steadyEncoderDrain,
              scheduledPeriodicDrain,
              drainProgress.madeCodecProgress,
              drainProgress.encodedFrameOutputs,
              SystemClock.elapsedRealtime()
            )
          ) {
            requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock);
            System.err.println(
              "ENCODER_LIVENESS state=sync_requested fps_target=" +
                TicketCaptureCadenceScheduler.FIXED_FPS +
                " consecutive_empty_drains=" + outputLiveness.consecutiveEmptyDrains()
            );
          }
          output.flush();
        } finally {
          // lockHardwareCanvas/unlockCanvasAndPost queues GPU work asynchronously. Keep the
          // hardware-backed capture result alive until this frame's encoder drain;
          // closing it immediately after posting can make the encoder reuse an older frame.
          source.close();
        }
        long encodeFinished = SystemClock.elapsedRealtime();
        // Primer posts are internal encoder work for one captured source picture. Keep --frames
        // probe semantics and the public frame count tied to captured pictures, not primer posts.
        sent += 1;
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (sent <= 3 || started - lastMetricAt >= 1_000L) {
          lastMetricAt = started;
          System.err.println(
            "METRIC capture_ms=" + (captureFinished - captureStarted) +
              " draw_ms=" + (drawFinished - drawStarted) +
              " encode_ms=" + (encodeFinished - encodeStarted) +
              " frame_ms=" + elapsed +
              " fps_target=" + TicketCaptureCadenceScheduler.FIXED_FPS +
              " frame_interval_ms=" + TicketCaptureCadenceScheduler.INTERVAL_MILLIS +
              " deadline_misses=" + cadenceScheduler.deadlineMisses() +
              " skipped_ticks=" + cadenceScheduler.skippedTicks() +
              " deadline_late_ms=" + cadenceDecision.latenessMillis +
              " last_skipped_ticks=" + cadenceDecision.skippedTicks +
              " frame_dependency_mode=all_intra" +
              " visibility=" + (visible ? "visible" : "blocked") +
              " secure_layers=true protected_content=true method=secure_screen_capture"
          );
        }
      }
      encoder.signalEndOfInputStream();
      drainEncoder(
        encoder,
        output,
        outputAssembler,
        true,
        100_000L,
        startupPrimer.finished() ? null : startupPrimer
      );
      if (!startupPrimer.finished()) {
        startupPrimer.finish();
        logStartupPrimerComplete(
          startupPrimer.firstKeyFrameForwarded() ? "keyframe_eos" : "fallback_eos_no_keyframe",
          startupPrimer
        );
      }
      outputAssembler.reset();
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
    AtomicBoolean captureActivated,
    CountDownLatch activationLatch,
    long activationToken,
    long startupRequestedAtMillis,
    Object frameWaitLock,
    TicketCaptureCadenceScheduler cadenceScheduler
  ) {
    Thread thread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String cmd = line.trim();
          if (cmd.equals("activate:" + activationToken) && activationToken > 0L) {
            if (captureActivated.compareAndSet(false, true)) {
              System.err.println(
                "HELPER_STARTUP phase=activation request_elapsed_ms=" +
                  Math.max(0L, SystemClock.elapsedRealtime() - startupRequestedAtMillis)
              );
              activationLatch.countDown();
            }
          } else if (cmd.equals("keyframe")) {
            requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock);
          } else if (
            cmd.startsWith("control_code_visual_probe:") ||
            cmd.startsWith("control_code_request_visual_probe:") ||
            cmd.startsWith("control_code_submit_visual_probe:") ||
            cmd.startsWith("control_code_cleanup_visual_probe:") ||
            cmd.startsWith("ticket_detail_visual_probe:") ||
            cmd.startsWith("ticket_activated_visual_probe:") ||
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
            boolean ticketDetail = cmd.startsWith("ticket_detail_visual_probe:");
            boolean activatedTicket = cmd.startsWith("ticket_activated_visual_probe:");
            boolean ticketCurrentOnly = cmd.startsWith("ticket_current_visual_probe:");
            boolean ticketAction = cmd.startsWith("ticket_action_visual_probe:") || ticketCurrentOnly;
            controlCodeVisualProbeRequest.set(new ControlCodeVisualProbeRequest(
              probeId,
              ticketAction ? "ticket_action" : (activatedTicket ? "ticket_activated" : (ticketDetail ? "ticket_detail" : (cleanup ? "control_code_cleanup" : (submitLayout ? "control_code_submit_layout" : "control_code_after_ok")))),
              cleanup,
              submitLayout,
              activatedTicket,
              ticketAction,
              ticketCurrentOnly,
              SystemClock.elapsedRealtime() + CONTROL_CODE_VISUAL_PROBE_MILLIS
            ));
            requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock);
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
    final boolean activatedTicket;
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
      boolean activatedTicket,
      boolean ticketAction,
      boolean ticketCurrentOnly,
      long untilMillis
    ) {
      this.id = id;
      this.reason = reason;
      this.cleanup = cleanup;
      this.submitLayout = submitLayout;
      this.activatedTicket = activatedTicket;
      this.ticketAction = ticketAction;
      this.ticketCurrentOnly = ticketCurrentOnly;
      this.untilMillis = new AtomicLong(untilMillis);
    }

    static ControlCodeVisualProbeRequest idle() {
      return new ControlCodeVisualProbeRequest(0L, "idle", false, false, false, false, false, 0L);
    }
  }

  private static void waitForNextFrame(
    Object frameWaitLock,
    TicketCaptureCadenceScheduler cadenceScheduler,
    long millis
  ) throws InterruptedException {
    synchronized (frameWaitLock) {
      if (!cadenceScheduler.hasImmediateCapturePending()) {
        frameWaitLock.wait(millis);
      }
    }
  }

  private static void requestImmediateSyncFrame(
    AtomicBoolean syncFrameRequested,
    TicketCaptureCadenceScheduler cadenceScheduler,
    Object frameWaitLock
  ) {
    synchronized (frameWaitLock) {
      syncFrameRequested.set(true);
      if (cadenceScheduler.requestImmediateCapture(SystemClock.elapsedRealtime())) {
        frameWaitLock.notifyAll();
      }
    }
  }

  private static StartupPrimerRun runStartupPrimer(
    MediaCodec encoder,
    Surface inputSurface,
    Bitmap source,
    Rect sourceCrop,
    Rect destination,
    Paint paint,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    TicketEncoderStartupPrimer primer
  ) throws Exception {
    TicketEncoderDrainProgress drainProgress = TicketEncoderDrainProgress.empty();
    long drawDurationMillis = 0L;

    while (primer.canPostAnotherInput() && !primer.firstKeyFrameForwarded()) {
      long nowMillis = SystemClock.elapsedRealtime();
      long waitMillis = primer.millisUntilNextInput(nowMillis);
      if (waitMillis > 0L) {
        drainProgress = drainProgress.plus(
          drainEncoder(
            encoder,
            output,
            outputAssembler,
            false,
            Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, waitMillis) * 1_000L,
            primer
          )
        );
        if (primer.firstKeyFrameForwarded()) {
          output.flush();
        }
        continue;
      }

      requestSyncFrame(encoder);
      long drawStarted = SystemClock.elapsedRealtime();
      drawBitmap(inputSurface, source, sourceCrop, destination, paint);
      long inputPostedAt = SystemClock.elapsedRealtime();
      drawDurationMillis += Math.max(0L, inputPostedAt - drawStarted);
      primer.noteInputPosted(inputPostedAt);
      drainProgress = drainProgress.plus(
        drainEncoder(
          encoder,
          output,
          outputAssembler,
          false,
          STARTUP_PRIMER_DRAIN_POLL_MILLIS * 1_000L,
          primer
        )
      );
      if (primer.firstKeyFrameForwarded()) {
        output.flush();
      }
    }

    if (!primer.firstKeyFrameForwarded() && primer.inputPosts() >= primer.inputLimit()) {
      long finalDrainDeadline = primer.lastInputAtMillis() + STARTUP_PRIMER_FINAL_DRAIN_MILLIS;
      while (!primer.firstKeyFrameForwarded() && SystemClock.elapsedRealtime() < finalDrainDeadline) {
        long remainingMillis = Math.max(1L, finalDrainDeadline - SystemClock.elapsedRealtime());
        drainProgress = drainProgress.plus(
          drainEncoder(
            encoder,
            output,
            outputAssembler,
            false,
            Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, remainingMillis) * 1_000L,
            primer
          )
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
        drainProgress = drainProgress.plus(
          drainEncoder(
            encoder,
            output,
            outputAssembler,
            false,
            Math.min(STARTUP_PRIMER_DRAIN_POLL_MILLIS, remainingMillis) * 1_000L,
            primer
          )
        );
      }
      output.flush();
      System.err.println(
        "ENCODER_STARTUP_PRIMER state=first_keyframe result=keyframe" +
          " input_limit=" + primer.inputLimit() +
          " input_posts=" + primer.inputPosts() +
          " media_outputs=" + primer.mediaOutputs() +
          " suppressed_outputs=" + primer.suppressedMediaOutputs() +
          " first_keyframe_ms=" + primer.firstKeyFrameLatencyMillis()
      );
    } else {
      // Do not open the gate here. The vendor may have delayed rather than dropped these Surface
      // posts. The first ordinary one-FPS drain will forward one complete keyframe, suppress every
      // sibling it dequeues, and then finish the gate without relying on a residual frame count.
      primer.beginFallbackWait();
      System.err.println(
        "ENCODER_STARTUP_PRIMER state=fallback_wait result=no_keyframe" +
          " input_limit=" + primer.inputLimit() +
          " input_posts=" + primer.inputPosts() +
          " media_outputs=" + primer.mediaOutputs() +
          " suppressed_outputs=" + primer.suppressedMediaOutputs() +
          " first_keyframe_ms=-1"
      );
    }

    return new StartupPrimerRun(drawDurationMillis, drainProgress);
  }

  private static void logStartupPrimerComplete(
    String result,
    TicketEncoderStartupPrimer primer
  ) {
    System.err.println(
      "ENCODER_STARTUP_PRIMER state=complete result=" + result +
        " input_posts=" + primer.inputPosts() +
        " media_outputs=" + primer.mediaOutputs() +
        " suppressed_outputs=" + primer.suppressedMediaOutputs() +
        " first_keyframe_ms=" + primer.firstKeyFrameLatencyMillis()
    );
  }

  private static final class StartupPrimerRun {
    final long drawDurationMillis;
    final TicketEncoderDrainProgress drainProgress;

    StartupPrimerRun(
      long drawDurationMillis,
      TicketEncoderDrainProgress drainProgress
    ) {
      this.drawDurationMillis = drawDurationMillis;
      this.drainProgress = drainProgress;
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

  private static void captureSecurePngBase64(
      int sourceWidth,
      int sourceHeight,
      int targetWidth,
      int targetHeight,
      int cropLeftSource,
      int cropTopSource,
      int cropRightSource,
      int cropBottomSource) throws Exception {
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
      Rect sourceCrop = sourceCropRect(
        cropLeftSource,
        cropTopSource,
        cropRightSource,
        cropBottomSource,
        sourceWidth,
        sourceHeight
      );
      boolean wholeSource = sourceCrop.left == 0 && sourceCrop.top == 0 &&
        sourceCrop.right == readableSource.getWidth() &&
        sourceCrop.bottom == readableSource.getHeight();
      if (targetWidth > 0 && targetHeight > 0 &&
          (targetWidth != readableSource.getWidth() || targetHeight != readableSource.getHeight() ||
            !wholeSource)) {
        encodedSource = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(encodedSource);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(
          readableSource,
          sourceCrop,
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
    boolean ticketDetailProbe,
    boolean activatedTicketProbe,
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
        String wire = result.wire();
        long registrationCards = result.cards.stream()
          .filter(card -> card.registrationBounds != null)
          .count();
        long latestCards = result.cards.stream().filter(card -> card.latest).count();
        String safeActionDiagnostic = result.state.equals("unknown")
          ? (ticketCurrentOnly
            ? TicketVisualActionClassifier.currentVisualDiagnostic(pixels)
            : TicketVisualActionClassifier.visualDiagnostic(pixels))
          : result.state.equals("ticket_list")
          ? "list_cards_" + result.cards.size() +
            "_registration_" + registrationCards + "_latest_" + latestCards +
            "_" + TicketVisualActionClassifier.registrationDiagnostic(pixels)
          : (result.state.endsWith("_detail") && result.backBounds == null)
          ? "detail_close_unproved"
          : "state_proved";
        String diagnostic = " diagnostic=" + safeActionDiagnostic;
        return new VisualProbeClassification(
          result.state,
          "",
          "",
          "",
          "",
          "",
          "",
          (wire.substring(("state=" + result.state).length()).trim() + diagnostic).trim()
        );
      }
      String state;
      if (activatedTicketProbe) {
        state = TicketControlCodeVisualClassifier.classifyForActivatedTicket(pixels);
      } else if (cleanupProbe) {
        state = TicketControlCodeVisualClassifier.classifyForCleanupHighResolution(pixels);
      } else {
        state = TicketControlCodeVisualClassifier.classify(pixels);
      }
      String sliderBounds = "";
      if (ticketDetailProbe) {
        sliderBounds = TicketControlCodeVisualClassifier.registrationSliderBounds(pixels);
      }
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

  private static TicketEncoderDrainProgress drainEncoder(
    MediaCodec encoder,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    boolean endOfStream,
    long timeoutUs
  )
    throws Exception {
    return drainEncoder(encoder, output, outputAssembler, endOfStream, timeoutUs, null);
  }

  private static TicketEncoderDrainProgress drainEncoder(
    MediaCodec encoder,
    OutputStream output,
    TicketH264EncoderOutputAssembler outputAssembler,
    boolean endOfStream,
    long timeoutUs,
    TicketEncoderStartupPrimer startupPrimer
  )
    throws Exception {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    TicketEncoderDrainProgress drainProgress = TicketEncoderDrainProgress.empty();
    while (true) {
      int index = encoder.dequeueOutputBuffer(info, timeoutUs);
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (endOfStream) {
          continue;
        }
        forwardSelectedBoundaryAccessUnit(output, startupPrimer);
        return drainProgress;
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
          TicketEncoderStartupPrimer.OutputDisposition disposition =
            TicketEncoderStartupPrimer.OutputDisposition.FORWARD;
          if (emitted != null && startupPrimer != null) {
            disposition = startupPrimer.classifyCompleteAccessUnit(
                emitted.payload,
                emitted.containsVcl,
                emitted.idrKeyFrame,
                SystemClock.elapsedRealtime()
              );
          }
          if (
            emitted != null &&
            disposition == TicketEncoderStartupPrimer.OutputDisposition.FORWARD
          ) {
            output.write(emitted.payload);
          }
          // Progress describes fully assembled codec output, including intentionally suppressed
          // primer media. Liveness must not restart a healthy encoder merely because its duplicate
          // startup picture was kept off the public pipe.
          drainProgress = drainProgress.plus(TicketEncoderDrainProgress.fromDequeuedAccessUnit(
            data.length,
            emitted == null ? 0 : emitted.payload.length,
            emitted != null && emitted.containsVcl
          ));
          if (eos) {
            outputAssembler.reset();
          }
        }
      } finally {
        // Every dequeued buffer must be returned even if assembly, gating, or pipe I/O fails.
        encoder.releaseOutputBuffer(index, false);
      }
      if (eos) {
        forwardSelectedBoundaryAccessUnit(output, startupPrimer);
        return drainProgress;
      }
      if (!endOfStream) {
        timeoutUs = 0L;
      }
    }
  }

  static void forwardSelectedBoundaryAccessUnit(
    OutputStream output,
    TicketEncoderStartupPrimer startupPrimer
  ) throws Exception {
    if (startupPrimer == null || !startupPrimer.boundaryDrainActive()) {
      return;
    }
    byte[] selected = startupPrimer.completeBoundaryDrain();
    if (selected == null) {
      return;
    }
    output.write(selected);
    startupPrimer.noteBoundaryAccessUnitForwarded();
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
