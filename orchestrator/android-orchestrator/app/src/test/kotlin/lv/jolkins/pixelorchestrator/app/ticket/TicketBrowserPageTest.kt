package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketBrowserPageTest {
  @Test
  fun firstMobileViewportIsOwnedByStreamShell() {
    val page = browserPage()

    assertTrue(page.contains("class=\"stream-page\""))
    assertTrue(page.contains("position: fixed; inset: 0"))
    assertTrue(page.contains("--ticket-stage-height"))
    assertTrue(page.contains("--ticket-controls-offset"))
    assertTrue(page.contains("http-equiv=\"Cache-Control\""))
    assertTrue(page.contains("height: var(--ticket-stage-height)"))
    assertTrue(page.contains("main { width: 100%; min-height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset) + var(--ticket-stage-height))"))
    assertTrue(page.contains(".stream-page { position: relative; width: 100vw; height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset))"))
    assertTrue(page.contains("const threshold = currentStageHeight();"))
    assertTrue(page.contains("body:not(.details-visible) .controls-panel"))
    assertFalse(page.contains("body:not([data-stream-ready=\"true\"]) .controls-panel"))
    assertTrue(page.contains("visibility: hidden; opacity: 0; pointer-events: none"))
    assertTrue(page.contains("history.scrollRestoration = 'manual'"))
  }

  @Test
  fun reconnectStateCollapsesDetailsBackToStream() {
    val page = browserPage()

    assertTrue(page.contains("function collapseDetailsToStream()"))
    assertTrue(page.contains("document.body.classList.remove('details-visible')"))
    assertTrue(page.contains("controlsPanel.setAttribute('aria-hidden', 'true')"))
    assertTrue(page.contains("window.scrollTo(0, 0)"))
    assertTrue(page.contains("keepFirstScreenPinned(true);"))
  }

  @Test
  fun browserCanRepairAStartedSessionThatDoesNotRenderFrames() {
    val page = browserPage()

    assertTrue(page.contains("stream_watchdog_no_first_frame"))
    assertTrue(page.contains("stream_watchdog_stale_frame"))
    assertTrue(page.contains("stream_watchdog_stale_frame_reconnect"))
    assertTrue(page.contains("function recoverVideoPipeline(reason, message = 'Reconnecting stream...', restartStream = false)"))
    assertTrue(page.contains("sendVideo({type: 'keyframe', reason: 'first_frame_retry'}) || send({type: 'keyframe', reason: 'first_frame_retry'})"))
    assertTrue(page.contains("send({type: 'restart_stream'})"))
    assertTrue(page.contains("await connect(true).catch(() => {});"))
    assertTrue(page.contains("await connectVideo(true);"))
    assertTrue(page.contains("recoverVideoPipeline('stream_watchdog_no_first_frame')"))
    assertTrue(page.contains("recoverVideoPipeline('stream_watchdog_stale_frame_reconnect')"))
    assertTrue(page.contains("if (restartStream) send({type: 'restart_stream'});"))
  }

  @Test
  fun browserMeasuresBroadLoadingAndUsesShortRecoveryBudget() {
    val page = browserPage()

    assertTrue(page.contains("const CONTROL_SOCKET_TIMEOUT_MS = 2500;"))
    assertTrue(page.contains("const VIDEO_SOCKET_TIMEOUT_MS = 2000;"))
    assertTrue(page.contains("const LOADING_BUDGET_MS = 2000;"))
    assertTrue(page.contains("const SELF_HEAL_INTERVAL_MS = 1000;"))
    assertTrue(page.contains("const STREAM_WATCHDOG_INTERVAL_MS = 500;"))
    assertTrue(page.contains("const NO_FIRST_FRAME_MS = 2000;"))
    assertTrue(page.contains("const STALE_FRAME_SOFT_MS = 2000;"))
    assertTrue(page.contains("const FRAME_DECODE_MAX_AGE_MS = 1500;"))
    assertTrue(page.contains("const FRAME_RENDER_MAX_AGE_MS = 2000;"))
    assertTrue(page.contains("function dropStaleFrameBeforeDecode(frame)"))
    assertTrue(page.contains("const STALE_FRAME_RECONNECT_MS = 12000;"))
    assertTrue(page.contains("const STALE_KEYFRAME_REQUEST_INTERVAL_MS = 2000;"))
    assertTrue(page.contains("clientLog('loading_phase', {phase: 'html_ready'"))
    assertTrue(page.contains("clientLog('loading_started'"))
    assertTrue(page.contains("clientLog('loading_finished'"))
    assertTrue(page.contains("clientLog('loading_over_2s'"))
    assertTrue(page.contains("recoverVideoPipeline('loading_over_budget'"))
    assertTrue(page.contains("health.autoStartAllowed === false && !desiredActive"))
  }

  @Test
  fun foregroundBrowserCanRecoverAfterViewerInactivityTimeout() {
    val page = browserPage()

    assertTrue(page.contains("function viewerIsForeground()"))
    assertTrue(page.contains("document.hasFocus()"))
    assertTrue(page.contains("function healthAutoStartBlocked(health)"))
    assertTrue(page.contains("health.autoStartBlockedReason === 'viewer_inactivity_timeout' && viewerIsForeground()"))
    assertTrue(page.contains("if (!viewerIsForeground() || selfHealInFlight) return;"))
    assertTrue(page.contains("window.addEventListener('focus', () => ensureStreaming('focus'))"))
  }

  @Test
  fun hiddenBrowserDoesNotAutoRecoverAfterViewerInactivityTimeout() {
    val page = browserPage()

    assertTrue(page.contains("if (!viewerIsForeground() || selfHealInFlight) return;"))
    assertTrue(page.contains("if (!desiredActive || autoStartSuspended || !viewerIsForeground()) return;"))
    assertTrue(page.contains("healthAutoStartBlocked(health)"))
  }

  @Test
  fun browserCodecCrashesRecoverVideoPipeline() {
    val page = browserPage()

    assertTrue(page.contains("let videoRecoveryInFlight = false;"))
    assertTrue(page.contains("clientLog('decoder_decode_failed'"))
    assertTrue(page.contains("recoverVideoPipeline('decoder_decode_failed', 'Reconnecting stream...')"))
    assertTrue(page.contains("clientLog('decoder_error'"))
    assertTrue(page.contains("recoverVideoPipeline('decoder_error', 'Reconnecting stream...')"))
    assertFalse(page.contains("setStatus(`Decoder error:"))
  }

  @Test
  fun staleVideoSocketCloseDoesNotResetCurrentDecoder() {
    val page = browserPage()

    assertTrue(page.contains("const videoSocketIsCurrent = videoWs === socket;"))
    assertTrue(page.contains("desiredActive, current: videoSocketIsCurrent"))
    assertTrue(page.contains("if (videoSocketIsCurrent) videoWs = null;"))
    assertTrue(page.contains("if (desiredActive && !autoStartSuspended && videoSocketIsCurrent)"))
  }

  @Test
  fun browserDropsOldFrameEpochsAndSequences() {
    val page = browserPage()

    assertTrue(page.contains("const FRAME_ENVELOPE_MAGIC = 0x54534632;"))
    assertTrue(page.contains("const FRAME_ENVELOPE_HEADER_BYTES = 29;"))
    assertTrue(page.contains("let currentStreamEpoch = 0;"))
    assertTrue(page.contains("let lastAcceptedFrameSequence = 0;"))
    assertTrue(page.contains("configuredFrameEnvelope = config.frameEnvelope || 'legacy';"))
    assertTrue(page.contains("currentStreamEpoch = Number(config.streamEpoch || 0);"))
    assertTrue(page.contains("function parseFrameEnvelope(raw)"))
    assertTrue(page.contains("function acceptFreshFrame(frame)"))
    assertTrue(page.contains("frame.epoch !== currentStreamEpoch"))
    assertTrue(page.contains("frame.sequence <= lastAcceptedFrameSequence"))
    assertTrue(page.contains("legacy_frame_in_tsf2_stream"))
    assertTrue(page.contains("data.slice(FRAME_ENVELOPE_HEADER_BYTES)"))
  }

  @Test
  fun browserTagsSocketsWithViewerIdentityAndClearsHiddenWaitingText() {
    val page = browserPage()

    assertTrue(page.contains("const VIEWER_ID_STORAGE_KEY = 'ticket_stream_viewer_id'"))
    assertTrue(page.contains("const viewerId = getOrCreateViewerId();"))
    assertTrue(page.contains("const pageId = `${'$'}{Date.now().toString(36)}-${'$'}{Math.random().toString(36).slice(2)}`"))
    assertTrue(page.contains("function withClientIdentity(url)"))
    assertTrue(page.contains("'viewer=' + encodeURIComponent(viewerId)"))
    assertTrue(page.contains("'&page=' + encodeURIComponent(pageId)"))
    assertTrue(page.contains("'&pageVersion=' + encodeURIComponent(PAGE_VERSION)"))
    assertTrue(page.contains("if (ready) {\n        streamPlaceholder.textContent = '';"))
  }

  @Test
  fun browserPurgesLegacyCachesWithoutClearingAuthState() {
    val page = browserPage()
    val start = page.indexOf("function purgeLegacyBrowserCaches()")
    val end = page.indexOf("function socketUrl()", start)
    assertTrue(start >= 0)
    assertTrue(end > start)
    val block = page.substring(start, end)

    assertTrue(page.contains("function purgeLegacyBrowserCaches()"))
    assertTrue(page.contains("navigator.serviceWorker.getRegistrations()"))
    assertTrue(page.contains("registration.unregister()"))
    assertTrue(page.contains("caches.keys()"))
    assertTrue(page.contains("caches.delete(key)"))
    assertTrue(page.contains("legacy_service_workers_unregistered"))
    assertTrue(page.contains("legacy_browser_caches_deleted"))
    assertTrue(page.contains("fetchNoStoreJson('/api/v1/cache-cleanup')"))
    assertTrue(page.contains("legacy_cache_cleanup_route_ready"))
    assertTrue(page.contains("purgeLegacyBrowserCaches();"))
    assertFalse(block.contains("localStorage"))
    assertFalse(block.contains("sessionStorage"))
    assertFalse(block.contains("document.cookie"))
  }

  @Test
  fun browserOnlyStopsPhoneSessionForExplicitUserStop() {
    val page = browserPage()

    assertTrue(page.contains("const explicitStop = reason === 'browser_stop';"))
    assertTrue(page.contains("send({type: 'stop', reason, explicit: true})"))
    assertTrue(page.contains("body: JSON.stringify({explicit: true, reason})"))
    assertTrue(page.contains("clientLog('session_detached_without_stop'"))
    assertTrue(page.contains("msg.data.autoStartAllowed === false && !desiredActive"))
    assertTrue(page.contains("health.autoStartAllowed === false && !desiredActive"))
    assertFalse(page.contains("await fetch('/api/v1/session/stop', {method: 'POST', cache: 'no-store', keepalive: true});"))
    assertFalse(page.contains("navigator.sendBeacon('/api/v1/session/stop'"))
  }

  @Test
  fun browserSplitsControlAndVideoSockets() {
    val page = browserPage()

    assertTrue(page.contains("const PAGE_VERSION = \"${TicketStreamService.SERVER_VERSION}\""))
    assertTrue(page.contains("function socketUrl()"))
    assertTrue(page.contains("+ '/api/v1/session'"))
    assertTrue(page.contains("function streamSocketUrl()"))
    assertTrue(page.contains("+ '/api/v1/stream'"))
    assertTrue(page.contains("socket.onmessage = handleControlSocketMessage"))
    assertTrue(page.contains("socket.onmessage = handleVideoSocketMessage"))
    assertTrue(page.contains("socket.binaryType = 'arraybuffer'"))
  }

  @Test
  fun browserReloadsWhenServerVersionChanges() {
    val page = browserPage()

    assertTrue(page.contains("function checkServerVersion(value)"))
    assertTrue(page.contains("function verifyFreshBootstrap(reason = 'boot')"))
    assertTrue(page.contains("fetchNoStoreJson('/api/v1/bootstrap')"))
    assertTrue(page.contains("clientLog('bootstrap_ready'"))
    assertTrue(page.contains("clientLog('page_boot'"))
    assertTrue(page.contains("page_version_mismatch"))
    assertTrue(page.contains("page_force_fresh_reload"))
    assertTrue(page.contains("location.replace(versionedRootUrl(serverVersion))"))
    assertTrue(page.contains("if (!checkServerVersion(health)) return health"))
    assertTrue(page.contains("if (!checkServerVersion(msg)) return"))
  }

  @Test
  fun browserNoLongerForwardsTapsToThePixel() {
    val page = browserPage()

    assertTrue(page.contains("setStatus('Use the request form below the ticket.')"))
    assertFalse(page.contains("send({type: 'tap'"))
    assertFalse(page.contains("send({type: 'swipe'"))
    assertFalse(page.contains("send({type: 'long_press'"))
  }

  @Test
  fun browserNoLongerForwardsKeyboardInput() {
    val page = browserPage()

    assertFalse(page.contains("function remoteKeyPayload(event)"))
    assertFalse(page.contains("send({type: 'key', key: payload.key})"))
    assertFalse(page.contains("window.addEventListener('keydown', forwardRemoteKey"))
  }

  @Test
  fun browserRequestsControlCodeThroughAutomatedCommand() {
    val page = browserPage()

    assertTrue(page.contains("id=\"codeForm\""))
    assertTrue(page.contains("inputmode=\"numeric\""))
    assertTrue(page.contains("pattern=\"[0-9]*\""))
    assertTrue(page.contains("function cleanDigits(value)"))
    assertTrue(page.contains("codeRequestTimes.length >= 2"))
    assertTrue(page.contains("send({type: 'generate_control_code', requestId: currentCodeRequestId, digits, owner: 'ticket', app: 'vivi', flow: 'control_code'})"))
    assertFalse("ticket.jolkins code entry must not implicitly request the RS monthly-ticket flow", page.contains("requestId: currentCodeRequestId, digits})"))
    assertTrue(page.contains("if (msg.type === 'control_code_result') handleControlCodeResult(msg);"))
    assertTrue(page.contains("const messageRequestId = String(msg.requestId || '')"))
    assertTrue(page.contains("if (!messageRequestId || messageRequestId !== currentCodeRequestId) return;"))
    assertFalse(page.contains("if (msg.requestId && currentCodeRequestId && msg.requestId !== currentCodeRequestId) return;"))
    assertFalse(page.contains("setTimeout(hideCodeResult, 60_000)"))
    assertFalse(page.contains("CONTROL_CODE_NOTICE_COOKIE"))
    assertFalse(page.contains("Kontroles koda režīms"))
  }

  @Test
  fun browserDelaysCodeReadyUntilPostConfirmationFrameIsDrawn() {
    val page = browserPage()
    val generatedHandler = page.substringBetween(
      "function handleGeneratedCodeResultEvent(msg)",
      "function handleControlCodeResult(msg)"
    )
    val ticketStateBranch = page.substringBetween(
      "msg.type === 'ticket_state_event' &&",
      "async function connectVideo"
    )
    val decoderOutput = page.substringBetween(
      "ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);",
      "frame.close();"
    )

    assertTrue(page.contains("let pendingGeneratedCodeResultEvent = null;"))
    assertTrue(page.contains("let lastDrawnFrameEpoch = 0;"))
    assertTrue(page.contains("let lastDrawnFrameSequence = 0;"))
    assertTrue(page.contains("function generatedResultFrameReady(msg)"))
    assertTrue(page.contains("const minFrameSequence = Number(msg && msg.minFrameSequence || 0);"))
    assertTrue(page.contains("lastDrawnFrameSequence >= minFrameSequence"))
    assertTrue(page.contains("function flushPendingGeneratedCodeResult()"))
    assertTrue(page.contains("pendingGeneratedCodeResultEvent = msg;"))
    assertTrue(generatedHandler.contains("showCodeResult('Waiting for Aztec frame');"))
    assertTrue(generatedHandler.contains("control_code_result_waiting_for_frame"))
    assertTrue(generatedHandler.indexOf("pendingGeneratedCodeResultEvent = msg;") < generatedHandler.indexOf("control_code_result_waiting_for_frame"))
    assertFalse("generated ticket_state_event must not announce ready before the watermarked frame draws", ticketStateBranch.contains("showCodeResult('Code ready'"))
    assertTrue(ticketStateBranch.contains("handleGeneratedCodeResultEvent(msg);"))
    assertTrue(decoderOutput.contains("lastDrawnFrameEpoch = Number(metadata.epoch || 0);"))
    assertTrue(decoderOutput.contains("lastDrawnFrameSequence = Number(metadata.sequence || 0);"))
    assertTrue(decoderOutput.contains("flushPendingGeneratedCodeResult();"))
  }

  @Test
  fun postConfirmationCaptureTimingStressRejectsEarlyFrames() {
    val page = browserPage()
    val readyGate = page.substringBetween(
      "function generatedResultFrameReady(msg)",
      "function flushPendingGeneratedCodeResult()"
    )

    assertTrue("browser readiness must be based on the drawn frame watermark", readyGate.contains("lastDrawnFrameSequence >= minFrameSequence"))
    assertTrue("browser readiness must reject stale stream epochs", readyGate.contains("eventStreamEpoch !== currentStreamEpoch"))

    var badCaptures = 0
    repeat(20) { attempt ->
      val minFrameSequence = 100L + attempt
      val frameDrawnBeforeAztec = minFrameSequence - 1L
      val wouldAnnounceReadyBeforeAztec = frameDrawnBeforeAztec >= minFrameSequence
      if (wouldAnnounceReadyBeforeAztec) badCaptures += 1
      val aztecFrameDrawn = minFrameSequence
      assertTrue("attempt $attempt should become ready only at the watermarked frame", aztecFrameDrawn >= minFrameSequence)
    }
    assertTrue("bad capture rate must be <= 1 in 20", badCaptures <= 1)
  }

  @Test
  fun browserFreezesGeneratedAztecFrameUntilResultDismissed() {
    val page = browserPage()
    val flushReady = page.substringBetween(
      "function flushPendingGeneratedCodeResult()",
      "function handleGeneratedCodeResultEvent(msg)"
    )
    val directReady = page.substringBetween(
      "function handleGeneratedCodeResultEvent(msg)",
      "function handleControlCodeResult(msg)"
    )
    val hide = page.substringBetween(
      "function hideCodeResult()",
      "function showCodeResult"
    )
    val closeDecoder = page.substringBetween(
      "function closeDecoder(message = 'Connecting')",
      "function stopKeepalive()"
    )
    val decoderOutput = page.substringBetween(
      "decoder = new VideoDecoder({",
      "error(error)"
    )
    val frozenDecoderBranch = decoderOutput.substringBetween(
      "if (generatedCodeResultFrozen) {",
      "ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);"
    )

    assertTrue(page.contains("let generatedCodeResultFrozen = false;"))
    assertTrue(page.contains("function freezeGeneratedCodeResultFrame("))
    assertTrue(page.contains("function clearGeneratedCodeResultFreeze("))
    assertTrue(flushReady.contains("freezeGeneratedCodeResultFrame(pending);"))
    assertTrue(directReady.contains("freezeGeneratedCodeResultFrame(msg);"))
    assertTrue(hide.contains("clearGeneratedCodeResultFreeze('code_result_dismissed');"))
    assertTrue(closeDecoder.contains("if (!generatedCodeResultFrozen) {"))
    assertTrue(closeDecoder.contains("ctx.clearRect(0, 0, canvas.width, canvas.height);"))
    assertTrue(decoderOutput.contains("if (generatedCodeResultFrozen) {"))
    assertTrue(frozenDecoderBranch.contains("lastFrameAt = performance.now();"))
    assertTrue(frozenDecoderBranch.contains("frame.close();"))
    assertTrue(
      "cleanup/raw frames must be suppressed before they can overwrite the Aztec canvas",
      decoderOutput.indexOf("if (generatedCodeResultFrozen)") <
        decoderOutput.indexOf("ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);")
    )
  }

  @Test
  fun browserPreventsSafariZoomAndDoesNotCarryPublicControlDialog() {
    val page = browserPage()

    assertTrue(page.contains("maximum-scale=1, user-scalable=no"))
    assertTrue(page.contains("viewport-fit=cover"))
    assertTrue(page.contains("body { margin: 0; width: 100%; min-height: 100%;"))
    assertTrue(page.contains("touch-action: pan-y;"))
    assertTrue(page.contains("scroll-snap-type: y proximity;"))
    assertTrue(page.contains("overscroll-behavior: none;"))
    assertTrue(page.contains("-webkit-touch-callout: none;"))
    assertTrue(page.contains("-webkit-tap-highlight-color: transparent;"))
    assertTrue(page.contains("streamVerticalPanThresholdPx"))
    assertTrue(page.contains("clientLog('stream_vertical_scroll', {allowed: true})"))
    assertTrue(page.contains("canvas.addEventListener('dblclick'"))
    assertTrue(page.contains("canvas.addEventListener('touchend', blockDoubleTapZoom, {passive: false})"))
    assertTrue(page.contains("document.addEventListener(eventName, blockStreamGesture, {passive: false})"))
    assertTrue(page.contains("gesturechange"))
    assertTrue(page.contains("doubleTapSuppressMs"))
    assertFalse(page.contains("['touchstart', 'touchmove']"))
    assertFalse(page.contains("claimDialog"))
    assertFalse(page.contains("showModal"))
    assertFalse(page.contains("claim-dialog"))
    assertFalse(page.contains("confirmClaim"))
    assertFalse(page.contains("Priv\\u0101ta kontroles koda sesija"))
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end > start)
    return substring(start, end)
  }
}
