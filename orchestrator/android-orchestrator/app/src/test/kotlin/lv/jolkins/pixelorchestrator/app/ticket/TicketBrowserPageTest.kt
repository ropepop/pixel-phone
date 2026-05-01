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
    assertTrue(page.contains("sendVideo({type: 'keyframe'}) || send({type: 'keyframe'})"))
    assertTrue(page.contains("send({type: 'restart_stream'})"))
    assertTrue(page.contains("connectVideo(true).catch(() => scheduleStartupReconnect())"))
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
    assertTrue(page.contains("page_version_mismatch"))
    assertTrue(page.contains("location.replace(nextUrl)"))
    assertTrue(page.contains("if (!checkServerVersion(health)) return health"))
    assertTrue(page.contains("if (!checkServerVersion(msg)) return"))
  }

  @Test
  fun browserStillOnlyForwardsTapsToThePixel() {
    val page = browserPage()

    assertTrue(page.contains("send({type: 'tap'"))
    assertFalse(page.contains("send({type: 'swipe'"))
    assertFalse(page.contains("send({type: 'long_press'"))
  }
}
