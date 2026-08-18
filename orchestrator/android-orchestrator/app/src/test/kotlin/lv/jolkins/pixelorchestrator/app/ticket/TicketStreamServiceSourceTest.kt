package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for the lean Ticket runtime.
 *
 * These tests deliberately describe product behavior and retained boundaries rather than the
 * names and ordering of retired coordinators. Runtime policy and classifiers remain covered by
 * their dedicated executable unit tests.
 */
class TicketStreamServiceSourceTest {
  private val service by lazy { source("ticket/TicketStreamService.kt") }
  private val config by lazy { source("ticket/TicketScreenConfig.kt") }
  private val h264Engine by lazy { source("ticket/TicketRootHardwareH264CaptureEngine.kt") }
  private val h264Main by lazy { source("ticket/TicketRootHardwareH264CaptureMain.java") }
  private val rootInput by lazy { source("ticket/TicketControlCodeRootInput.kt") }
  private val viviEnforcer by lazy { source("ticket/TicketViviPageEnforcer.kt") }
  private val spacetimeWorker by lazy { source("ticket/TicketSpacetimeWorker.kt") }
  private val reselectCommandPolicy by lazy { source("ticket/TicketLatestTicketReselectCommandPolicy.kt") }

  @Test
  fun runtimeEventsUseOneBoundedSpacetimeSinkWithoutLogcat() {
    val record = body(service, "private fun recordTicketEvent", "private fun enqueueTicketSpacetimeTraceEvent")
    assertFalse(service.contains("android.util.Log"))
    assertFalse(service.contains("Log."))
    assertTrue(record.contains("event.take(96)"))
    assertTrue(record.contains("TicketTracePrivacy.allowlistedFields(detail)"))
    assertTrue(record.contains("enqueueTicketSpacetimeTraceEvent(cleanEvent, safeFields)"))
  }

  @Test
  fun eventSinkCarriesComparableWallAndPhoneClocks() {
    val sink = body(service, "private fun enqueueTicketSpacetimeTraceEvent", "private fun shouldPublishTicketTraceEvent")
    assertTrue(sink.contains("System.currentTimeMillis()"))
    assertTrue(sink.contains("SystemClock.elapsedRealtime()"))
    assertTrue(sink.contains("put(\"eventAtEpochMillis\""))
    assertTrue(sink.contains("put(\"eventAtPhoneUptimeMillis\""))
    assertTrue(sink.contains("detailFields.forEach"))
    assertFalse(sink.contains("put(\"detail\""))
    assertFalse(sink.contains("viewerId"))
    assertTrue(sink.contains("enqueueTicketSpacetimePhoneMessage(message)"))
  }

  @Test
  fun startupTraceOncePhasesAreResetPerSessionAndDeduplicated() {
    val begin = body(service, "private fun beginStartupTrace", "private fun recordStartupTracePhase")
    val phase = body(service, "private fun recordStartupTracePhase", "private fun beginTicketWake")
    assertTrue(begin.contains("startupTraceOncePhases.clear()"))
    assertTrue(phase.contains("if (once)"))
    assertTrue(phase.contains("startupTraceOncePhases.add(cleanName)"))
    assertTrue(phase.contains("if (!first) return"))
  }

  @Test
  fun controlResultFastDrainRepeeksAfterOneMessage() {
    val drain = body(
      spacetimeWorker,
      "private suspend fun drainPhoneMessagesUntilControlCodeResult",
      "private suspend fun drainControlCodeCleanupHandoff"
    )
    assertTrue(drain.contains("drainPhoneMessages(config, client, CONTROL_CODE_HOT_PHONE_MESSAGE_DRAIN_LIMIT)"))
    assertFalse(drain.contains("drainPhoneMessages(config, client)"))
  }

  @Test
  fun ticketStateAndOperationalLogsUseSeparateCloudDatabases() {
    val safeLog = body(
      spacetimeWorker,
      "fun safeLog",
      "private suspend fun call(reducer: String"
    )
    val loggingSelection = body(
      spacetimeWorker,
      "val operationalLoggingDatabase = boundedOperationalLoggingDatabase",
      "return TicketSpacetimeConfig"
    )
    val queue = body(
      spacetimeWorker,
      "internal class TicketOperationalLogQueue",
      "internal class TicketSpacetimeWorker"
    )
    val sender = body(
      spacetimeWorker,
      "private class TicketOperationalLogHttpSender",
      "private fun safeOperationalLogId"
    )
    val tracePublisher = body(
      spacetimeWorker,
      "private suspend fun publishTicketTraceEvent",
      "private fun JsonObject.string"
    )
    assertTrue(spacetimeWorker.contains("OPERATIONAL_LOGGING_DATABASE"))
    assertTrue(spacetimeWorker.contains("DEFAULT_OPERATIONAL_LOGGING_DATABASE = \"operational-logging-prod\""))
    assertTrue(safeLog.contains("operationalLogQueue ?: return"))
    assertTrue(safeLog.contains("queue.enqueue"))
    assertFalse(safeLog.contains("callDatabase"))
    assertFalse(safeLog.contains("ticketremote_append_safe_operational_log"))
    assertTrue(spacetimeWorker.contains("operationallog_append_ticket_event"))
    assertTrue(spacetimeWorker.contains("boundedTicketOperationalDetailJson(event.detailJson)"))
    assertTrue(spacetimeWorker.contains("BufferOverflow.DROP_OLDEST"))
    assertTrue(spacetimeWorker.contains("events.trySend(event)"))
    assertTrue(spacetimeWorker.contains("TICKET_OPERATIONAL_LOG_HTTP_TIMEOUT_MILLIS = 1_500"))
    assertTrue(tracePublisher.contains("ticketTraceOperationalDetailJson("))
    assertFalse(tracePublisher.contains("eventAtEpochMillis.toString()"))
    assertTrue(sender.contains("connectTimeout = httpTimeoutMillis"))
    assertTrue(sender.contains("readTimeout = httpTimeoutMillis"))
    assertTrue(queue.contains("events.close()"))
    assertTrue(queue.contains("senderJob.cancel()"))
    assertFalse(queue.contains("File("))
    assertFalse(queue.contains("SQLite"))
    assertTrue(loggingSelection.contains("logOperationalLoggingDisabled()"))
    assertFalse(loggingSelection.contains("return null"))
    assertTrue(spacetimeWorker.contains("callDatabase(config.database, reducer, args)"))
  }

  @Test
  fun spacetimeHttpWritesAreBoundedAndConnectionsAreAlwaysClosed() {
    val boundedOperation = body(
      spacetimeWorker,
      "private suspend fun <T> boundedTicketHttpOperation",
      "internal object TicketSpacetimePollingPolicy"
    )
    val clientPost = body(
      spacetimeWorker,
      "private suspend fun post(path: String, contentType: String, body: String): String",
      "private fun decodeSQLRows"
    )
    val logPost = body(
      spacetimeWorker,
      "private suspend fun post(path: String, body: String)",
      "private fun postBlocking(path: String, body: String)"
    )
    assertTrue(boundedOperation.contains("withTimeout(timeoutMillis.toLong())"))
    assertTrue(boundedOperation.contains("runInterruptible"))
    assertTrue(clientPost.contains("boundedTicketHttpOperation(config.httpTimeoutMillis)"))
    assertTrue(logPost.contains("boundedTicketHttpOperation(httpTimeoutMillis)"))
    assertTrue(spacetimeWorker.contains("setFixedLengthStreamingMode(bodyBytes.size)"))
    assertTrue(spacetimeWorker.contains("finally {\n      connection.disconnect()"))
  }

  @Test
  fun malformedPhoneMessagesAndWorkerFailuresNeverPersistRawContent() {
    val publish = body(
      spacetimeWorker,
      "private suspend fun publishPhoneMessage",
      "private fun isControlCodeResultPayload"
    )
    assertTrue(publish.contains("\"inputCategory\" to \"invalid_json\""))
    assertTrue(publish.contains("\"inputLength\" to message.length"))
    assertTrue(publish.contains("\"inputCategory\" to \"unsupported_type\""))
    assertFalse(publish.contains("message.take"))
    assertFalse(publish.contains("\"message\" to message"))
    assertFalse(spacetimeWorker.contains("safeDetail("))
    assertFalse(spacetimeWorker.contains("error.message"))
    assertTrue(spacetimeWorker.contains("ticketOperationalErrorCategory(error)"))
    assertTrue(spacetimeWorker.contains("hashedOperationalIdentifier(correlationId)"))
  }

  @Test
  fun traceSinkIsAllowlistedAndErrorTextIsBounded() {
    val allowlist = body(service, "private fun shouldPublishTicketTraceEvent", "private fun safeErrorDetail")
    val safeError = body(service, "private fun safeErrorDetail", "private fun safeRootFailure")
    assertTrue(allowlist.contains("event.startsWith(\"control_code_\")"))
    assertTrue(allowlist.contains("event.startsWith(\"spacetime_\")"))
    assertTrue(allowlist.contains("event.startsWith(\"stream_\")"))
    assertTrue(allowlist.contains("event.startsWith(\"ticket_brightness_\")"))
    assertTrue(allowlist.contains("event == \"ticket_state_event\""))
    assertTrue(safeError.contains("split(Regex(\"\\\\s+\"))"))
    assertTrue(safeError.contains("take(MAX_TICKET_EVENT_DETAIL_BYTES)"))
  }

  @Test
  fun pixelLocalViewerAndCacheBootstrapAreRetired() {
    val http = body(service, "private suspend fun handleHttpClient", "private suspend fun acceptWebSocket")
    assertTrue(http.contains("path == \"/\" || path == \"/api/v1/bootstrap\" || path == \"/api/v1/cache-cleanup\""))
    assertTrue(http.contains("sendText(output, 410, \"Pixel-local viewer retired; use the public Ticket service\")"))
    assertFalse(service.contains("internal fun browserPage()"))
    assertFalse(service.contains("VideoDecoder"))
    assertFalse(service.contains("EncodedVideoChunk"))
  }

  @Test
  fun healthSessionAndH264InterfacesRemain() {
    val http = body(service, "private suspend fun handleHttpClient", "private suspend fun acceptWebSocket")
    assertTrue(http.contains("path == \"/api/v1/health\""))
    assertTrue(http.contains("path == \"/api/v1/session/start\""))
    assertTrue(http.contains("path == \"/api/v1/session/recover\""))
    assertTrue(http.contains("path == \"/api/v1/session/stop\""))
    assertTrue(http.contains("path == \"/api/v1/stream\""))
    assertTrue(http.contains("video = path == \"/api/v1/stream\""))
  }

  @Test
  fun arbitraryNonRsBrowserInputIsAbsent() {
    assertFalse(service.contains("private suspend fun handleRemoteKey"))
    assertFalse(service.contains("private suspend fun tap(inputId"))
    assertFalse(service.contains("private suspend fun swipe("))
    assertFalse(service.contains("REMOTE_QUICK_CLAIM"))
    assertFalse(service.contains("remote_control_code_snap_tap"))
  }

  @Test
  fun nonVideoControlSocketIsRestrictedToRsCompatibility() {
    val control = body(service, "private suspend fun handleClientCommand", "private fun handleVideoClientCommand")
    assertTrue(control.contains("generate_rigassatiksme_qr_batch"))
    assertTrue(control.contains("cancel_rigassatiksme_qr_batch"))
    assertTrue(control.contains("rigassatiksme_login_start"))
    assertTrue(control.contains("rigassatiksme_login_sms"))
    assertFalse(control.contains("generate_control_code"))
    assertFalse(control.contains("prepare_control_code"))
    assertFalse(control.contains("\"tap\" ->"))
    assertFalse(control.contains("\"key\" ->"))
  }

  @Test
  fun spacetimeIsTheTicketCommandAuthority() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    listOf(
      "\"start\" ->", "\"activity\" ->", "\"keyframe\" ->", "\"recover_stream\" ->",
      "\"force_ticket_reselect\" ->", "\"generate_control_code\" ->",
      "\"control_code_browser_capture\" ->", "\"control_exit\" ->"
    ).forEach { assertTrue("missing retained Spacetime command $it", commands.contains(it)) }
    assertTrue(commands.contains("spacetime_command_unsupported"))
  }

  @Test
  fun generateStartsWithoutAPreparationCommand() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val generate = commands.substringAfter("\"generate_control_code\" ->").substringBefore("\"control_code_browser_capture\" ->")
    assertFalse(commands.contains("\"prepare_control_code\" ->"))
    assertTrue(generate.contains("CoroutineStart.UNDISPATCHED"))
    assertTrue(generate.contains("handleGenerateControlCode("))
    assertTrue(generate.contains("generate_control_code_started"))
  }

  @Test
  fun controlCodeTimingUsesTheSpacetimeAdmissionClockAndExpiringTraceSink() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val summary = body(service, "private fun recordControlCodeTimingSummary", "private fun sendTicketStateEvent")
    assertTrue(commands.contains("serverSentAt = payload?.stringValue(\"serverSentAt\").orEmpty().ifBlank { command.createdAt }"))
    assertTrue(generate.contains("parseControlCodeServerSentAtMillis(serverSentAt)"))
    assertTrue(generate.contains("control_code_database_to_phone"))
    assertTrue(generate.contains("recordControlCodeTimingSummary("))
    assertTrue(summary.contains("database_to_phone_ms"))
    assertTrue(summary.contains("database_to_first_tap_ms"))
    assertTrue(summary.contains("database_to_popup_ready_ms"))
    assertTrue(summary.contains("database_to_browser_ack_ms"))
    assertTrue(summary.contains("popup_open_ms"))
    assertTrue(summary.contains("popup_to_first_input_ms"))
    assertTrue(summary.contains("phone_to_browser_ack_ms"))
    assertTrue(summary.contains("phone_to_ticket_detail_ready_ms"))
    assertTrue(summary.contains("phone_to_ok_tap_ms"))
    assertTrue(summary.contains("database_to_result_ms"))
    assertTrue(summary.contains("database_to_finished_ms"))
    assertTrue(summary.contains("control_code_timing_${'$'}stage"))
    assertTrue(service.contains("Instant.parse(cleanValue).toEpochMilli()"))
    assertTrue(service.contains("spacetime_start_coalesced_control_code"))
    assertTrue(service.contains("spacetime_desired_start_coalesced_control_code"))
    assertTrue(service.contains("root_hardware_h264_startup_readiness_started"))
    assertTrue(service.contains("root_hardware_h264_startup_readiness_completed"))
    assertTrue(service.contains("control_code_root_capture_readiness_wait"))
    assertTrue(generate.contains("lastControlCodeRequestStatus = \"queued\""))
  }

  @Test
  fun interactionBoundariesUseTheFastAccessibilityTreeAndRetainRootVisualProof() {
    val preparation = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    val popup = body(
      service,
      "private suspend fun openControlCodePopupFastForRequest",
      "private fun markOpenedControlCodePopupTransactionReady"
    )
    assertTrue(preparation.contains("controlCodeRequestFastInteractionHierarchy"))
    assertTrue(popup.contains("controlCodeRequestFastInteractionHierarchy"))
    assertTrue(service.contains("fastVisibleHierarchy("))
    assertTrue(service.contains("CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT = 4"))
    assertTrue(service.contains("requestTicketDetailVisualProbe"))
    assertTrue(service.contains("control_code_fast_interaction_hierarchy_observed"))
  }

  @Test
  fun oneRealControlRequestOwnsThePhoneMutationLane() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(generate.contains("claimControlCodeAutomationForRequest()"))
    assertTrue(generate.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(generate.contains("controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)"))
    assertTrue(generate.contains("releaseControlCodeAutomationForRequest()"))
    assertTrue(generate.indexOf("claimControlCodeAutomationForRequest()") < generate.indexOf("controlCodePhoneMutationLane.withOwnership"))
  }

  @Test
  fun controlCodeAdmissionPreemptsBackgroundRootPreparationBeforeTakingTheLane() {
    val claim = body(service, "private suspend fun claimControlCodeAutomationForRequest", "private fun releaseControlCodeAutomationForRequest")
    assertTrue(claim.contains("clientDisconnectStopJob?.cancel()"))
    assertTrue(claim.contains("cancelForegroundGuard()"))
    assertTrue(claim.contains("cancelTicketRecovery(\"control_code_request_admitted\")"))
    assertTrue(claim.contains("cancelAndJoinRootHardwareH264CapturePreparation(\"control_code_request_admitted\")"))
    assertTrue(claim.contains("rooted_capture_preparation=cancelled_and_joined"))
    assertTrue(claim.contains("controlExitCleanupJob?.cancel()"))
  }

  @Test
  fun rootedCommandCancellationIsPropagatedAfterTheShellIsRestarted() {
    val worker = source("ticket/TicketRootCommandWorker.kt")
    assertTrue(worker.contains("catch (cancelled: CancellationException)"))
    assertTrue(worker.contains("runInterruptible"))
    assertTrue(worker.contains("restartLocked()"))
    assertTrue(worker.contains("throw cancelled"))
  }

  @Test
  fun coldTicketPreparationUsesOneCardTapAndParallelFinalProof() {
    val fastOpen = body(
      service,
      "private suspend fun observeTicketDetailForFastPublicOpenRootProof",
      "private suspend fun verifyFreshTicketDetailVisualProof"
    )
    assertTrue(fastOpen.contains("maxRecoveryActions = 1"))
    assertTrue(fastOpen.contains("extendBudgetAfterRecoveryAction = false"))
    assertTrue(fastOpen.contains("parallelTicketDetailProofAfterTicketCardSelection = true"))
    assertTrue(fastOpen.contains("state == TicketViviRecoveryState.TICKET_DETAIL && !observation.hierarchy.isNullOrBlank()"))
    assertTrue(fastOpen.contains("fast_open_root_ticket_detail_aztec_proved"))
    assertTrue(service.contains("private const val TICKET_FAST_PUBLIC_OPEN_LIST_RECOVERY_BUDGET_MILLIS = 8_000L"))
    val proof = body(
      service,
      "private suspend fun proveTicketDetailAfterFastCardSelection",
      "private suspend fun attemptWakeRecoveryActionForRootWake"
    )
    assertTrue(proof.contains("val hierarchyDeferred = async"))
    assertTrue(proof.contains("val visualProofDeferred = async"))
    assertTrue(proof.contains("fast_open_ticket_card_detail_parallel_proof"))
  }

  @Test
  fun rootCapturePreparationIsCoalescedAndCancellable() {
    val schedule = body(
      service,
      "private fun scheduleRootHardwareH264CaptureStart",
      "private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership"
    )
    assertTrue(schedule.contains("rootHardwareH264CapturePreparationJob"))
    assertTrue(schedule.contains("root_hardware_h264_prepare_coalesced"))
    assertTrue(schedule.contains("CoroutineStart.LAZY"))
    assertTrue(service.contains("private fun cancelRootHardwareH264CapturePreparation"))
  }

  @Test
  fun fastStateRevisionSelectsTheSinglePreflightPath() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val preflight = body(service, "private suspend fun ensureControlCodeRequestPreflight", "private fun markTicketNonTouchAction")
    assertTrue(generate.contains("val fastStateAcceptedAtAdmission = controlCodeFastStateRevisionAccepted(cleanFastRevision)"))
    assertTrue(generate.contains("ensureControlCodeRequestPreflight("))
    assertTrue(preflight.contains("controlCodeFastStateRevisionAccepted"))
    assertTrue(preflight.contains("fast_state_revision_missed"))
    assertTrue(preflight.contains("reuseRecentTicketDetailForControlCode(phases, requestStartedAtMillis)"))
    assertTrue(preflight.contains("ensureTicketSessionForControlCodeRequest(phases, requestStartedAtMillis)"))
    assertTrue(preflight.contains("fast_state_inline_preparation_accepted"))
    assertFalse(preflight.contains("controlCodeFastVisualMarkerFresh"))
    assertFalse(preflight.contains("controlCodeFastColdStartMarkerAccepted"))
    assertTrue(service.contains("activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(service.contains("control_code_request_existing_stream"))
    assertTrue(service.contains("timeoutMillis = CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS"))
    assertTrue(service.contains("prepareCaptureWithCurrentPhoneMutationOwnership = true"))
    assertTrue(service.contains("prepareTicketDetailForControlCodeRequest(phases, requestStartedAtMillis)"))
    assertTrue(service.contains("streamEpoch == revisionEpoch && frameSequence >= revisionSequence"))
  }

  @Test
  fun stalePreparationAllowsOneMoreRootedDetailProofWithoutChangingTheActionPath() {
    val preparation = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    assertTrue(preparation.contains("detailProofRetryAttempted"))
    assertTrue(preparation.contains("control_code_inline_preparation_detail_proof_retry"))
    assertTrue(service.contains("CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS = 8_000L"))
    assertFalse(preparation.contains("display_geometry"))
    assertFalse(preparation.contains("fallback"))
  }

  @Test
  fun fastStateReusesTheFreshRootedDetailHierarchyWithoutASecondPrefetch() {
    val preflight = body(service, "private suspend fun ensureControlCodeRequestPreflight", "private fun markTicketNonTouchAction")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(preflight.contains("recentTicketDetailHierarchyWithin"))
    assertFalse(preflight.contains("controlCodeRequestRootHierarchy"))
    assertFalse(generate.contains("ticketDetailHierarchyDeferred"))
    assertFalse(generate.contains("control_code_root_prefetch"))
    assertTrue(service.contains("hierarchy = hierarchy"))
  }

  @Test
  fun sessionStartupPublishesFastReadyFromItsRootedTicketProof() {
    val frame = body(
      service,
      "private fun handleRootHardwareH264CaptureFrame",
      "private fun scheduleRootHardwareSecureCaptureProbe"
    )
    val publisher = body(
      service,
      "private fun publishControlCodeFastReadyAfterSessionProof",
      "private fun markControlCodeFastReady"
    )
    assertTrue(frame.contains("publishControlCodeFastReadyAfterSessionProof()"))
    assertTrue(publisher.contains("lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(publisher.contains("recentPreparedControlCodeTicketDetailHierarchy(nowMillis)"))
    assertTrue(publisher.contains("markControlCodeFastReady(\"session_start_ticket_detail\")"))
  }

  @Test
  fun staleFastStateReusesOneFreshRootedDetailProofBeforeDumpingAgain() {
    val preflight = body(
      service,
      "private suspend fun ensureTicketSessionForControlCodeRequest",
      "private suspend fun prepareTicketDetailForControlCodeRequest"
    )
    val reuseIndex = preflight.indexOf("recentPreparedControlCodeTicketDetailHierarchy()?.let")
    val freshDumpIndex = preflight.indexOf("return prepareTicketDetailForControlCodeRequest(phases, requestStartedAtMillis)")
    assertTrue(reuseIndex >= 0)
    assertTrue(freshDumpIndex > reuseIndex)
    assertTrue(preflight.contains("source=session_start_proof"))
    assertTrue(preflight.contains("source=root_capture_preparation_proof"))
    assertTrue(service.contains("private fun recentRootedTicketDetailProofForControlCode"))
    assertTrue(preflight.contains("recentRootedTicketDetailProofForControlCode()?.let"))
    assertTrue(preflight.contains("source=root_h264_recent_session_proof fast_revision_missed=true"))
  }

  @Test
  fun staleRevisionUsesFreshRootedAztecSamplesBeforeRepeatingTheRootDump() {
    val preflight = body(service, "private suspend fun ensureControlCodeRequestPreflight", "private fun markTicketNonTouchAction")
    val reuse = body(service, "private suspend fun reuseRecentTicketDetailForControlCode", "private fun markTicketNonTouchAction")
    assertTrue(preflight.contains("reuseRecentTicketDetailForControlCode(phases, requestStartedAtMillis)"))
    assertTrue(reuse.contains("recentTicketDetailHierarchyWithin"))
    assertTrue(reuse.contains("TicketViviPageEnforcer.isTicketDetail(hierarchy)"))
    assertTrue(reuse.contains("ticketSessionState != TICKET_SESSION_CONTROL_EXIT"))
    assertTrue(reuse.contains("verifyFreshTicketDetailVisualProof("))
    assertTrue(reuse.contains("updateTicketSessionState(TICKET_SESSION_LIVE, \"control_code_recent_detail_reused\")"))
    assertTrue(reuse.contains("CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS"))
    assertTrue(reuse.contains("source=root_h264_recent_detail_visual_proof fast_revision_missed=true"))
  }

  @Test
  fun requestAdmissionStartsKeyboardClampAsynchronouslyAndReleasesItInFinally() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(generate.contains("RequestScopedKeyboardClampLease"))
    assertTrue(generate.contains("lease.startAsync()"))
    assertTrue(service.contains("awaitControlCodeKeyboardClamp"))
    assertTrue(service.contains("CoroutineStart.DEFAULT"))
    assertTrue(service.contains("keyboard_clamp_requested"))
    assertTrue(service.contains("keyboard_clamp_applied"))
    assertTrue(service.contains("keyboard_clamp_released"))
    assertTrue(generate.contains("withContext(NonCancellable)"))
    assertTrue(generate.indexOf("lease.startAsync()") < generate.indexOf("startControlCodeRequestBurst"))
    assertTrue(service.contains("TicketControlCodeKeyboardClamp.buildAcquireScript()"))
    assertTrue(service.contains("TicketControlCodeKeyboardClamp.buildRestoreScript(state"))
    assertTrue(service.contains("keyboard_clamp_restore_failed"))
    assertTrue(service.contains("keyboard_clamp_late_apply_reverted"))
  }

  @Test
  fun acceptedControlRequestPublishesCriticalRunningProgressBeforePhoneWork() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val progress = body(service, "private fun sendControlCodeProgress", "private fun sendRigassatiksmeQrResult")
    val running = "sendControlCodeProgress(cleanRequestId, \"running\", \"phone_request_started\")"
    val preflight = "ensureControlCodeRequestPreflight("
    assertTrue(generate.contains(running))
    assertTrue(generate.indexOf(running) < generate.indexOf(preflight))
    assertTrue(progress.contains("put(\"type\", \"control_code_progress\")"))
    assertTrue(progress.contains("enqueueTicketSpacetimePhoneMessage(message)"))
  }

  @Test
  fun controlCodeRequestValidationAndOwnershipRemainStrict() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(service.contains("Regex(\"\"\"^[0-9]{2,8}${'$'}\"\"\")"))
    assertTrue(generate.contains("missing_request_id"))
    assertTrue(generate.contains("invalid_code"))
    assertTrue(generate.contains("command_owner_flow_required"))
    assertTrue(generate.contains("unsupported_qr_source"))
    assertTrue(generate.contains("unsupported_qr_flow"))
    assertTrue(generate.contains("wrong_command_owner"))
  }

  @Test
  fun streamRunsOneFpsIdleAndFourFpsDuringControlDispatch() {
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_STEADY_FPS = 1"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_CONTROL_CODE_REQUEST_FPS = 8"))
    assertTrue(h264Engine.contains("controlCodeRequestFpsTarget = TicketScreenConfig.ROOT_HARDWARE_H264_CONTROL_CODE_REQUEST_FPS"))
    assertTrue(service.contains("targetFps = TicketScreenConfig.ROOT_HARDWARE_H264_STEADY_FPS"))
  }

  @Test
  fun fourFpsBurstBeginsAtBrowserDispatchBeforePhoneOwnershipWait() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val burst = "startControlCodeRequestBurst(\"control_code_browser_dispatch\")"
    assertTrue(generate.contains(burst))
    assertTrue(generate.indexOf(burst) < generate.indexOf("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(generate.contains("capture_burst_started"))
  }

  @Test
  fun fourFpsBurstStopsOnBrowserAckTimeoutAndFinally() {
    val wait = body(service, "private suspend fun waitForControlCodeBrowserCapture", "private suspend fun ensureTicketSessionForControlCodeRequest")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(wait.contains("stopControlCodeRequestBurst(\"browser_capture_acknowledged\")"))
    assertTrue(wait.contains("stopControlCodeRequestBurst(reason)"))
    assertTrue(wait.contains("control_code_browser_capture_ack_timeout"))
    assertTrue(generate.contains("stopControlCodeRequestBurst(\"control_code_request_finally\")"))
    assertTrue(service.contains("CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 20_000L"))
  }

  @Test
  fun browserFreezeAcknowledgementPrecedesPhoneCleanup() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val generated = "ticketState = TICKET_PIXEL_STATE_GENERATED_RESULT"
    val ack = "waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)"
    val cleanup = "returnControlCodeSurfaceToRawTicket("
    assertTrue(generate.contains(generated))
    assertTrue(generate.contains(ack))
    assertTrue(generate.indexOf(generated) < generate.indexOf(ack))
    assertTrue(generate.indexOf(ack) < generate.indexOf(cleanup, generate.indexOf(ack)))
  }

  @Test
  fun cleanupProofsOverlapOnlyWhereTheAckBarrierAllowsIt() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val clean = body(service, "private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup")
    val prefetch = body(service, "private fun startControlCodeCleanupCloseHierarchyPrefetch", "private suspend fun beginGeneratedControlCodeResultFastClose")
    assertTrue(generate.contains("startControlCodeCleanupCloseHierarchyPrefetch(phases)"))
    assertTrue(generate.indexOf("startControlCodeCleanupCloseHierarchyPrefetch(phases)") < generate.indexOf("waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)"))
    assertTrue(generate.indexOf("waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)") < generate.indexOf("returnControlCodeSurfaceToRawTicket("))
    assertTrue(prefetch.contains("CoroutineStart.DEFAULT"))
    assertFalse(prefetch.contains("CoroutineStart.UNDISPATCHED"))
    assertTrue(clean.contains("finalHierarchyPrefetch"))
    assertTrue(clean.contains("control_code_cleanup_final_ticket_detail_prefetch"))
    assertTrue(clean.contains("control_code_fast_cleanup_final_detail_reused"))
    assertTrue(clean.contains("TicketViviPageEnforcer.isTicketDetail(finalHierarchy)"))
  }

  @Test
  fun generatedResultCarriesFreshStreamWatermarkToBrowser() {
    val delivery = body(service, "private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun requestFreshControlCodeFrameWatermark")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(delivery.contains("markerFirstControlCodeFrameWatermarkForBrowser"))
    assertTrue(delivery.contains("streamEpoch = watermark.first"))
    assertTrue(delivery.contains("minFrameSequence = watermark.second"))
    assertTrue(generate.contains("resultFrameEpoch = delivery.streamEpoch"))
    assertTrue(generate.contains("resultMinFrameSequence = delivery.minFrameSequence"))
    assertTrue(generate.contains("resultProofAtMillis = delivery.resultProofAtMillis"))
  }

  @Test
  fun generatedResultKeepsTheCurrentEncoderEpochAndWaitsForARealKeyframe() {
    val marker = body(
      service,
      "private suspend fun markerFirstControlCodeFrameWatermarkForBrowser",
      "private suspend fun captureGeneratedControlCodeImageBytes"
    )
    val watermark = body(
      service,
      "private suspend fun requestFreshControlCodeFrameWatermark",
      "private suspend fun openControlCodePopupFastForRequest"
    )
    assertFalse(marker.contains("refreshRootHardwareH264ForControlCodeResult"))
    assertTrue(marker.contains("requestFreshControlCodeFrameWatermark(reason)"))
    assertTrue(watermark.contains("val startingEpoch = streamEpoch"))
    assertTrue(watermark.contains("val startingSequence = frameSequence"))
    assertTrue(watermark.contains("requestKeyFrame(reason)"))
    assertTrue(watermark.contains("currentKeyFrameSequence > startingSequence"))
    assertTrue(watermark.contains("control_code_request_result_marker_frame_wait_timeout"))
    assertTrue(service.contains("controlCodeResultEncoderRefreshActive"))
  }

  @Test
  fun generatedResultMarkerAllowsPopupFadeBeforeWatermarkingTheFrozenFrame() {
    val marker = body(
      service,
      "private suspend fun markerFirstControlCodeFrameWatermarkForBrowser",
      "private suspend fun captureGeneratedControlCodeImageBytes"
    )
    val delayEvent = "control_code_request_result_marker_delay"
    val watermarkCall = "requestFreshControlCodeFrameWatermark(reason)"
    assertTrue(service.contains("CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS = 200L"))
    assertTrue(marker.contains(delayEvent))
    assertTrue(marker.contains("measureInputPhase(phases, \"result_marker_delay\")"))
    assertTrue(marker.indexOf(delayEvent) < marker.indexOf(watermarkCall))
  }

  @Test
  fun restartedRootEncoderDropsFramesFromThePreviousProcess() {
    val stop = body(h264Engine, "private fun stopProcesses()", "private fun consumeCleanStopForFastStart")
    assertTrue(h264Engine.contains("private val captureGeneration = AtomicLong(0L)"))
    assertTrue(h264Engine.contains("val parserGeneration = captureGeneration.incrementAndGet()"))
    assertTrue(h264Engine.contains("if (parserGeneration == captureGeneration.get())"))
    assertTrue(stop.contains("captureGeneration.incrementAndGet()"))
  }

  @Test
  fun rootEncoderKeepsHardwareCaptureAliveUntilInputIsDrained() {
    val loop = body(h264Main, "while (frames <= 0 || sent < frames)", "encoder.signalEndOfInputStream()")
    val draw = loop.indexOf("drawBitmap(inputSurface")
    val drain = loop.indexOf("int drained = drainEncoder")
    val close = loop.indexOf("source.close()")
    assertTrue(draw >= 0)
    assertTrue(drain > draw)
    assertTrue(close > drain)
    assertTrue(loop.contains("hardware-backed capture result alive until this frame's encoder drain"))
  }

  @Test
  fun browserCaptureAckIsScopedToTheActiveRequestAndCandidateFrame() {
    val receive = body(service, "private fun handleControlCodeBrowserCapture", "private suspend fun waitForControlCodeBrowserCapture")
    val wait = body(service, "private suspend fun waitForControlCodeBrowserCapture", "private suspend fun ensureTicketSessionForControlCodeRequest")
    assertTrue(receive.contains("pendingControlCodeBrowserCaptureRequestId == cleanRequestId"))
    assertTrue(receive.contains("frameEpoch = frameEpoch"))
    assertTrue(receive.contains("frameSequence = frameSequence"))
    assertTrue(wait.contains("pendingControlCodeBrowserCaptureAck?.takeIf { it.requestId == requestId }"))
    assertTrue(service.contains("controlCodeResultEncoderRefreshActive = true"))
    assertTrue(wait.contains("controlCodeResultEncoderRefreshActive = false"))
  }

  @Test
  fun controlCodeOpenUsesFreshRootBoundsBeforeTyping() {
    val type = body(service, "private suspend fun executeRootControlCodeType", "private suspend fun tapControlCodePointWithoutKeyboard")
    val open = body(service, "private suspend fun openControlCodePopupFastForRequest", "private fun markOpenedControlCodePopupTransactionReady")
    assertTrue(open.contains("controlCodeButtonActionForHierarchy"))
    assertTrue(open.contains("controlCodePopupSurfaceForHierarchy"))
    assertTrue(open.contains("control_code_root_before_button"))
    assertFalse(open.contains("display_geometry"))
    assertFalse(open.contains("fallbackControlCodeButtonTarget"))
    assertTrue(open.contains("postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(type.contains("TicketControlCodeRootInput.buildTypeScript("))
    assertTrue(type.contains("openX = transaction.open?.x"))
    assertTrue(type.contains("openY = transaction.open?.y"))
  }

  @Test
  fun controlCodeFastPanelClampDoesNotAddASecondPostDelay() {
    assertTrue(service.contains("CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS = 0L"))
  }

  @Test
  fun requestLeaseDisablesTheImeBeforeHelperFocusAndRestoresIt() {
    assertTrue(rootInput.contains("settings put secure show_ime_with_hard_keyboard 0"))
    assertFalse(rootInput.contains("ime disable"))
    assertFalse(rootInput.contains("input text"))
    assertFalse(rootInput.contains("input tap"))
    val clamp = source("ticket/TicketControlCodeKeyboardClamp.kt")
    assertTrue(clamp.contains("ime disable"))
    assertTrue(clamp.contains("ime enable"))
    assertTrue(clamp.contains("ime set"))
    assertTrue(clamp.contains("enabled_input_methods"))
    assertTrue(clamp.contains("default_input_method"))
    assertTrue(service.contains("CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS = 4_000L"))
  }

  @Test
  fun codeValueMustBeVisuallyProvedBeforeExplicitSubmit() {
    val enter = body(service, "private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun executeRootControlCodeType")
    val proof = "waitForEnteredControlCodeValueVisualProof(phases)"
    val submit = "tapControlCodePointWithoutKeyboard("
    assertTrue(enter.contains(proof))
    assertTrue(enter.contains("valueProof != ControlCodeEnteredValueProof.VALUE_READY"))
    assertTrue(enter.contains("submit_blocked"))
    assertTrue(enter.contains(submit))
    assertTrue(enter.indexOf(proof) < enter.indexOf(submit))
  }

  @Test
  fun submitIsAnExplicitRootTapAndNotAKeyboardEnterKey() {
    val tap = body(service, "private suspend fun tapControlCodePointWithoutKeyboard", "private suspend fun waitForEnteredControlCodeValueVisualProof")
    assertTrue(tap.contains("input tap ${'$'}x ${'$'}y"))
    assertTrue(tap.contains("PhoneAutomationServiceBridge.markNonTouchInput"))
    assertTrue(tap.contains("CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS"))
    assertFalse(rootInput.contains("KEY_ENTER"))
    assertFalse(rootInput.contains("KEY_KPENTER"))
  }

  @Test
  fun unacknowledgedSubmitIsReconciledByVisualState() {
    val enter = body(service, "private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun executeRootControlCodeType")
    val delivery = body(service, "private suspend fun runFastControlCodeDeliveryForRequest", "private suspend fun requestFreshControlCodeFrameWatermark")
    assertTrue(enter.contains("proved_value_submit_tap_unacknowledged; reconciling_visual_state"))
    assertTrue(enter.contains("control_code_submit_attempted"))
    assertTrue(enter.contains("return true"))
    assertTrue(delivery.contains("waitForGeneratedControlCodeResultAfterSubmit("))
  }

  @Test
  fun generatedResultRequiresFreshVisualOrRootProof() {
    val wait = body(service, "private suspend fun waitForGeneratedControlCodeResultAfterSubmit", "private suspend fun waitForFreshControlCodeVisualProbe")
    assertTrue(wait.contains("visualProbe.result == \"generated\""))
    assertTrue(wait.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(wait.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(wait.contains("CONTROL_CODE_FAST_RESULT_FINAL_ROOT_DUMP_TIMEOUT_MILLIS"))
    assertFalse(wait.contains("raw_ticket_after_submit_generated"))
  }

  @Test
  fun cleanupUsesTheFreshDetectedBadgeCrossAndAOneShotRootInputPath() {
    val begin = body(service, "private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun finishGeneratedControlCodeResultFastCleanup")
    val send = body(service, "private suspend fun sendFastGeneratedResultCloseTap", "private suspend fun waitForCleanTicketSurfaceFast")
    val oneShot = body(service, "private suspend fun runFastOneShotControlSurfaceCloseInput", "private suspend fun runFastNonTouchWakeScript")
    assertTrue(begin.contains("var closeHierarchy = \"\""))
    assertTrue(begin.contains("for (attempt in 1..CONTROL_CODE_CLEANUP_HIERARCHY_REINSPECT_MAX_READS)"))
    assertTrue(begin.contains("delay(CONTROL_CODE_CLEANUP_HIERARCHY_REINSPECT_GAP_MILLIS)"))
    assertTrue(begin.contains("TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(closeHierarchy)"))
    assertFalse(begin.contains("geometry"))
    assertFalse(begin.contains("controlCodeResultBadgeGeometryCloseAction"))
    assertTrue(send.contains("input tap ${'$'}{action.x} ${'$'}{action.y}"))
    assertTrue(send.contains("runFastOneShotControlSurfaceCloseInput("))
    assertFalse(send.contains("runFastNonTouchInput("))
    assertTrue(service.contains("private val controlSurfaceCloseRootExecutor = SuRootExecutor()"))
    assertTrue(oneShot.contains("controlSurfaceCloseRootExecutor.run(command, timeout)"))
    assertFalse(oneShot.contains("wrapNonTouchPanelSleepClamp("))
    assertFalse(oneShot.contains("runScript("))
    assertTrue(oneShot.contains("CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS = 2_000L"))
    assertFalse(send.contains("runFastInlineControlResultCloseInput"))
    assertFalse(service.contains("private suspend fun runFastInlineControlResultCloseInput"))
  }

  @Test
  fun cleanupFailureCannotRemainPublishedAsSuccess() {
    val cleanup = body(
      spacetimeWorker,
      "private suspend fun publishControlCodeCleanup",
      "private suspend fun loadConfig"
    )
    assertTrue(cleanup.contains("val ok = payload.boolean(\"ok\") || payload.boolean(\"accepted\")"))
    assertTrue(cleanup.contains("status = if (ok) \"\" else \"failed\""))
    assertTrue(cleanup.contains("cleanupPending = !ok"))
    assertTrue(cleanup.contains("control_code_cleanup_attention_needed"))
  }

  @Test
  fun failedEntryCleanupReinspectsABlankHierarchyBeforeBroaderRecovery() {
    val cleanup = body(
      service,
      "private suspend fun returnControlCodeSurfaceToRawTicket",
      "private fun isActionableControlCodeExitHierarchy"
    )
    assertTrue(cleanup.contains("TicketControlCodeCleanupHierarchyResolver.resolve("))
    assertTrue(cleanup.contains("CONTROL_CODE_CLEANUP_HIERARCHY_REINSPECT_MAX_READS"))
    assertTrue(cleanup.contains("controlExitHierarchy().orEmpty()"))
    assertTrue(cleanup.contains("\"control_code_cleanup_hierarchy_reinspect\""))
    assertTrue(cleanup.contains("TicketViviRecoveryState.CONTROL_CODE_POPUP"))
    assertTrue(cleanup.contains("sendFastGeneratedResultCloseTap("))
  }

  @Test
  fun failedEntryPopupUsesTheSubmitProofAndBackBeforeBroadRecovery() {
    val cleanup = body(
      service,
      "private suspend fun returnControlCodeSurfaceToRawTicket",
      "private fun isActionableControlCodeExitHierarchy"
    )
    assertTrue(cleanup.contains("tryDismissOpenControlCodePopupAfterInputFailure("))
    assertTrue(cleanup.contains("generatedHierarchy.isBlank()"))
    val popup = body(
      service,
      "private suspend fun tryDismissOpenControlCodePopupAfterInputFailure",
      "private fun isActionableControlCodeExitHierarchy"
    )
    assertTrue(popup.contains("requestControlCodeSubmitVisualProbe"))
    assertTrue(popup.contains("input keyevent KEYCODE_BACK"))
    assertTrue(popup.contains("waitForCleanTicketSurfaceFast("))
    assertFalse(popup.contains("CONTROL_EXIT_DETAIL_RETURN_X_FRACTION"))
  }

  @Test
  fun finalReselectTraceRequiresConfirmedOperationalWriteBeforePhoneAck() {
    val drain = body(
      spacetimeWorker,
      "private suspend fun drainPhoneMessages",
      "private fun controlCodeRequestId"
    )
    val tracePublisher = body(
      spacetimeWorker,
      "private suspend fun publishTicketTraceEvent",
      "private fun JsonObject.string"
    )
    val queue = body(
      spacetimeWorker,
      "internal class TicketOperationalLogQueue",
      "private data class TicketPhoneMessagePublishOutcome"
    )
    assertTrue(drain.contains("if (!outcome.acknowledgePhoneMessage)"))
    assertTrue(drain.indexOf("if (!outcome.acknowledgePhoneMessage)") <
      drain.indexOf("service.acknowledgeTicketSpacetimePhoneMessage(message)"))
    assertTrue(tracePublisher.contains("event.startsWith(\"latest_ticket_reselect_final_\")"))
    assertTrue(tracePublisher.contains("client.safeLogRetained("))
    assertTrue(tracePublisher.contains("retainedTicketTraceOperationalLogId(payload, event)"))
    assertTrue(queue.contains("suspend fun sendRetained"))
    assertTrue(queue.contains("sender(event)"))
    assertTrue(queue.contains("catch (_: Throwable)"))
  }

  @Test
  fun staticBlankProofSettlesAndReprovesBeforeAnyNonOverlappingRetype() {
    val enter = body(service, "private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun executeRootControlCodeType")
    val firstProof = enter.indexOf("var valueProof = waitForEnteredControlCodeValueVisualProof(phases)")
    val settle = enter.indexOf("delay(CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS)")
    val secondProof = enter.indexOf("valueProof = waitForEnteredControlCodeValueVisualProof(phases)", settle)
    val leaseWait = enter.indexOf("remainingInitialKeyboardLeaseMillis(")
    val retype = enter.indexOf("\"control_code_root_virtual_keyboard_retype\"")
    assertTrue(firstProof >= 0)
    assertTrue(firstProof < settle)
    assertTrue(settle < secondProof)
    assertTrue(secondProof < leaseWait)
    assertTrue(leaseWait < retype)
    assertTrue(enter.contains("initialTypeCompletedAtMillis = initialTypeCompletedAtMillis"))
    assertTrue(enter.contains("safetyMarginMillis = CONTROL_CODE_ROOT_RETYPE_LEASE_MARGIN_MILLIS"))
    assertTrue(service.contains("CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS = 350L"))
    assertTrue(service.contains("CONTROL_CODE_ROOT_RETYPE_LEASE_MARGIN_MILLIS = 250L"))
  }

  @Test
  fun cleanupRefreshesTheGeneratedResultBoundsBeforeClosing() {
    val begin = body(service, "private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun finishGeneratedControlCodeResultFastCleanup")
    val request = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val returnRaw = body(service, "private suspend fun returnControlCodeSurfaceToRawTicket", "private suspend fun beginGeneratedControlCodeResultFastClose")
    assertFalse(request.contains("reuseGeneratedProof"))
    assertFalse(returnRaw.contains("reuseGeneratedProof"))
    assertFalse(begin.contains("reuseGeneratedProof"))
    assertTrue(begin.contains("controlExitHierarchy().orEmpty()"))
    assertTrue(begin.contains("TicketViviRecoveryState.CONTROL_CODE_RESULT"))
    assertTrue(begin.contains("TicketViviPageEnforcer::classifyForRecovery"))
  }

  @Test
  fun inlineCloseCoordinatesStillComeFromTheViviResultCross() {
    assertTrue(viviEnforcer.contains("close_control_code_result"))
    assertTrue(viviEnforcer.contains("controlCodeExitCloseActionForHierarchy"))
    assertFalse(service.contains("CONTROL_CODE_RESULT_BADGE_CLOSE_X_FRACTION"))
    assertFalse(service.contains("CONTROL_CODE_RESULT_BADGE_CLOSE_Y_FRACTION"))
    assertFalse(service.contains("reason = \"geometry_close_control_code_badge\""))
    assertFalse(viviEnforcer.contains("controlCodeResultGeometryCloseBounds"))
    assertFalse(service.contains("CONTROL_EXIT_RESULT_CLOSE_X_FRACTION"))
    assertFalse(service.contains("CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION"))
  }

  @Test
  fun fastCleanupProofBudgetStaysBelowTwoSeconds() {
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 1_400L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS = 200L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2"))
    assertTrue(service.contains("CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS = 0L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_ROOT_DUMP_TIMEOUT_MILLIS = 1_000L"))
  }

  @Test
  fun successfulBrowserCaptureTargetsAztecTicketDetailAndNeverTheRegistrationList() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val returnRaw = body(service, "private suspend fun returnControlCodeSurfaceToRawTicket", "private fun isActionableControlCodeExitHierarchy")
    val finish = body(service, "private suspend fun finishGeneratedControlCodeResultFastCleanup", "private suspend fun sendFastGeneratedResultCloseTap")
    assertFalse(generate.contains("returnToTicketListWithRegistrationButton = capture.ok"))
    assertFalse(generate.contains("recoverTicketRegistrationListForControlCodeRequest"))
    assertFalse(returnRaw.contains("returnToTicketListWithRegistrationButton"))
    assertTrue(generate.contains("returnControlCodeSurfaceToRawTicket"))
    assertTrue(finish.contains("cleanState != TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(service.contains("TicketControlCodeVisualClassifier.RAW_TICKET"))
    assertTrue(service.contains("completeFastVerifiedTicketDetailControlExitCleanup"))
  }

  @Test
  fun registrationListRecoveryStopsOnNestedButtonProof() {
    val recovery = body(service, "private suspend fun observeTicketDetailForWakeWithRoot", "private suspend fun attemptWakeRecoveryActionForRootWake")
    assertTrue(recovery.contains("requireTicketListWithRegistrationButton"))
    assertTrue(recovery.contains("isTicketListWithCardAndRegistrationButton"))
    assertTrue(recovery.contains("wake_root_ticket_list_with_registration_button"))
    assertTrue(recovery.contains("ticketDetailReturnToListActionForHierarchy"))
  }

  @Test
  fun activeGuardOpensTheCardBodyInsteadOfAcceptingTheRegistrationList() {
    val guard = body(service, "private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun attemptActiveGuardRecoveryAction")
    assertFalse(guard.contains("active_guard_ticket_list_ready"))
    assertFalse(guard.contains("isTicketListWithCardAndRegistrationButton"))
    assertTrue(service.contains("ticketCardDetailActionForHierarchy"))
    assertTrue(service.contains("state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD"))
  }

  @Test
  fun cleanupRequiresRepeatedRawTicketVisualProof() {
    val clean = body(service, "private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup")
    assertTrue(clean.contains("TicketControlCodeCleanupVisualProof(CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT)"))
    assertTrue(clean.contains("TicketControlCodeVisualClassifier.RAW_TICKET"))
    assertTrue(clean.contains("rawTicketConfirmed"))
    assertTrue(clean.contains("raw_ticket_fast_proof"))
  }

  @Test
  fun cleanupRetriesAsSoonAsAFreshGeneratedResultIsStillVisible() {
    val finish = body(service, "private suspend fun finishGeneratedControlCodeResultFastCleanup", "private suspend fun sendFastGeneratedResultCloseTap")
    val clean = body(service, "private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup")
    val generatedBranch = clean.substringAfter("if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT)")
      .substringBefore("val sampleGapRemainingMillis")
    assertTrue(finish.contains("returnOnFreshGeneratedResult = true"))
    assertTrue(finish.indexOf("returnOnFreshGeneratedResult = true") < finish.indexOf("control_code_fast_cleanup_close_retry"))
    assertTrue(clean.contains("returnOnFreshGeneratedResult: Boolean = false"))
    assertTrue(generatedBranch.contains("if (returnOnFreshGeneratedResult)"))
    assertTrue(generatedBranch.contains("control_code_fast_cleanup_result_still_visible"))
    assertTrue(generatedBranch.contains("return state"))
  }

  @Test
  fun cleanupPublishesRawStateOnlyAfterFreshFrameWhenRequired() {
    val complete = body(service, "private suspend fun completeControlExitCleanup", "private suspend fun waitForFreshStreamFrameAfterCleanup")
    val fresh = body(service, "private suspend fun waitForFreshStreamFrameAfterCleanup", "private fun recordControlExitCleanup")
    assertTrue(complete.contains("waitForFreshStreamFrameAfterCleanup(reason, cleanupStartedAtMillis)"))
    assertTrue(complete.contains("ticketState = TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(complete.indexOf("waitForFreshStreamFrameAfterCleanup") < complete.indexOf("TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(fresh.contains("requestKeyFrame(\"control_exit_cleanup\")"))
    assertTrue(fresh.contains("lastFrameSentAtMillis > baselineFrameAtMillis") || fresh.contains("frameAtMillis > baselineFrameAtMillis"))
  }

  @Test
  fun successFailureAndExceptionShareTheSameReturnToRawLifecycle() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertFalse(generate.contains("control_code_success_cleanup_recover"))
    assertTrue(generate.contains("control_code_request_failed_return_raw"))
    assertTrue(generate.contains("control_code_request_exception_return_raw"))
    assertTrue(Regex("returnControlCodeSurfaceToRawTicket\\(").findAll(generate).count() >= 3)
  }

  @Test
  fun controlExitCommandUsesTheSameReturnToRawLifecycle() {
    val exit = body(service, "private suspend fun runControlExitCleanup", "private suspend fun completeControlExitCleanup")
    assertTrue(exit.contains("returnControlCodeSurfaceToRawTicket"))
    assertTrue(exit.contains("prepareTicketDetailForControlCodeRequest"))
    assertTrue(exit.contains("TICKET_SESSION_NEEDS_ATTENTION"))
  }

  @Test
  fun duplicateBrowserCloseCannotReenterControlExitAfterRawCleanup() {
    val exit = body(service, "private fun scheduleControlExitSoftSettle", "private fun scheduleControlExitCleanup")
    assertTrue(exit.contains("ticketSessionState == TICKET_SESSION_LIVE"))
    assertTrue(exit.contains("!controlCodeModeActive"))
    assertTrue(exit.contains("lastControlCodeSurfaceState == null"))
    assertTrue(exit.contains("control_exit_already_clean"))
    assertTrue(exit.indexOf("control_exit_already_clean") < exit.indexOf("updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT"))
  }

  @Test
  fun lateControlSurfaceResetCannotReenterControlExitAfterLiveCleanup() {
    val reset = body(service, "private fun resetControlCodeMode", "private fun rememberControlCodeSurface")
    assertTrue(reset.contains("ticketSessionState != TICKET_SESSION_LIVE"))
    assertTrue(reset.indexOf("ticketSessionState != TICKET_SESSION_LIVE") < reset.indexOf("updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT"))
  }

  @Test
  fun nextRequestIsReleasedAndMarkedReadyAfterSuccessfulCleanup() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(generate.contains("markControlCodeFastReady(\"cleanup:${'$'}cleanupReason\")"))
    assertTrue(generate.contains("markControlCodeFastReady(\"failed_delivery_cleanup\")"))
    assertTrue(generate.contains("markControlCodeFastReady(\"exception_cleanup\")"))
    assertTrue(generate.contains("releaseControlCodeAutomationForRequest()"))
  }

  @Test
  fun rootHardwareH264IsTheOnlyTicketCaptureMode() {
    val start = body(service, "private suspend fun startTicketSessionLocked", "private fun tryReuseActiveHardwareStreamBeforePreflight")
    assertTrue(start.contains("rootHardwareH264CaptureEngine.probe()"))
    assertTrue(start.contains("activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(start.contains("scheduleRootHardwareH264CaptureStart"))
    assertFalse(start.contains("MediaProjection"))
    assertFalse(start.contains("FFMPEG"))
    assertFalse(config.contains("TicketFfmpegHealth"))
  }

  @Test
  fun coldOpenHasExplicitFiveSecondPublicBudget() {
    assertTrue(service.contains("TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L"))
    assertTrue(service.contains("remainingFastPublicOpenBudgetMillis"))
    assertTrue(service.contains("prepareViviForRootHardwareH264FastOpen"))
    assertTrue(service.contains("requireUnactivatedRegistration = requireUnactivatedRegistration"))
    assertTrue(service.contains("ticketDetailReturnToListActionForHierarchy"))
    assertTrue(service.contains("val listRecoveryStartedAtMillis = SystemClock.elapsedRealtime()"))
    assertFalse(service.contains("fastWakeReadyFromRecentTicketDetail(reason, wakeStartedAtMillis)"))
  }

  @Test
  fun coldOpenUsesOneAuthoritativeRootHierarchyProof() {
    val prepare = body(
      service,
      "private suspend fun prepareViviForRootHardwareH264FastOpen",
      "private fun scheduleRootHardwareH264CaptureStart"
    )
    assertTrue(prepare.contains("observeTicketDetailForFastPublicOpenRootProof"))
    assertFalse(prepare.contains("observeTicketDetailForFastPublicOpenVisibleProof"))
    assertTrue(prepare.contains("fresh H.264 Aztec samples"))
  }

  @Test
  fun coldOpenLaunchesViViThroughTheImmediateActivityIntent() {
    val launch = body(service, "private suspend fun launchViviForWake", "private fun remainingWakeBudgetMillis")
    assertTrue(launch.contains("val startedAtMillis = SystemClock.elapsedRealtime()"))
    assertTrue(launch.contains("launchVivi()"))
    assertFalse(launch.contains("runFastNonTouchWakeScript"))
    assertTrue(launch.contains("recordTicketEvent(\"wake_launch_vivi_root\""))
  }

  @Test
  fun coldOpenCanShowProvisionalRootFramesWithoutUnlockingTicketControls() {
    val prepare = body(
      service,
      "private suspend fun prepareViviForRootHardwareH264FastOpen",
      "private fun scheduleRootHardwareH264CaptureStart"
    )
    val schedule = body(
      service,
      "private fun scheduleRootHardwareH264CaptureStart",
      "private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership"
    )
    assertTrue(prepare.contains("allowProvisionalHardwareH264FramesForFocusedVivi"))
    assertTrue(prepare.contains("post_launch_stream"))
    assertTrue(prepare.contains("fast_public_open_post_launch_memory_skipped"))
    assertTrue(service.contains("hardware_h264_provisional_frames_allowed"))
    assertTrue(schedule.indexOf("prewarmRootHardwareH264CaptureIfPossible") < schedule.indexOf("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(service.contains("hardwareCaptureVerified = true"))
    assertTrue(service.contains("hardwareFrameBroadcastAllowed = true"))
  }

  @Test
  fun watchdogDoesNotRestartOrVerifyAProvisionalTicketStream() {
    val restart = body(service, "private fun restartActiveStreamEngine", "private fun scheduleStreamWatchdog")
    val watchdog = body(service, "private fun evaluateStreamWatchdog", "private fun configMessage")
    assertTrue(restart.contains("val verifiedBeforeRestart = hardwareCaptureVerified"))
    assertTrue(restart.contains("hardwareCaptureVerified = verifiedBeforeRestart"))
    assertTrue(watchdog.contains("!hardwareFrameBroadcastAllowed || !hardwareCaptureVerified"))
    assertTrue(watchdog.contains("waiting_ticket_ready"))
  }

  @Test
  fun publicOpenAndRootWakeNavigationTelemetryIsPublished() {
    val allowlist = body(service, "private fun shouldPublishTicketTraceEvent", "private fun safeErrorDetail")
    assertTrue(allowlist.contains("event.startsWith(\"wake_\")"))
    assertTrue(allowlist.contains("event.startsWith(\"fast_public_open_\")"))
    assertTrue(allowlist.contains("event == \"root_readiness\""))
  }

  @Test
  fun emptyForegroundViviTreeCanUseOnlyFreshRootH264RawTicketProof() {
    val fallback = body(
      service,
      "private suspend fun observeTicketDetailForFastPublicOpenRootH264VisualFallback",
      "private suspend fun prepareViviForRootHardwareH264FastOpen"
    )
    assertTrue(fallback.contains("hierarchyObservation.state != TicketViviRecoveryState.UNKNOWN_VIVI"))
    assertTrue(fallback.contains("hierarchyObservation.state != TicketViviRecoveryState.BLANK"))
    assertTrue(fallback.contains("viviFocusedForFastPublicOpen"))
    assertTrue(fallback.contains("requestTicketDetailVisualProbe"))
    assertTrue(fallback.contains("TicketControlCodeCleanupVisualProof(TICKET_FAST_PUBLIC_OPEN_VISUAL_RAW_TICKET_PROOF_COUNT)"))
    assertTrue(fallback.contains("TicketControlCodeVisualClassifier.RAW_TICKET"))
    assertTrue(fallback.contains("TicketControlCodeVisualClassifier.CONTROL_POPUP"))
    assertTrue(fallback.contains("TicketControlCodeVisualClassifier.GENERATED"))
    assertTrue(fallback.contains("source = \"root_h264_visual\""))
    assertFalse(fallback.contains("runFastNonTouchInput"))
    assertFalse(fallback.contains("PhoneAutomationServiceBridge"))
    assertTrue(h264Engine.contains("fun requestTicketDetailVisualProbe"))
    assertTrue(h264Engine.contains("fun requestActivatedTicketVisualProbe"))
    assertTrue(h264Engine.contains("ticket_detail_visual_probe"))
    assertTrue(h264Engine.contains("ticket_activated_visual_probe"))
    assertTrue(h264Main.contains("cmd.startsWith(\"ticket_detail_visual_probe:\")"))
    assertTrue(h264Main.contains("cmd.startsWith(\"ticket_activated_visual_probe:\")"))
  }

  @Test
  fun activeH264StreamCanBeReusedBeforeHeavyPreflight() {
    val start = body(service, "private suspend fun startTicketSessionLocked", "private fun tryReuseActiveHardwareStreamBeforePreflight")
    val reuse = body(service, "private fun tryReuseActiveHardwareStreamBeforePreflight", "private fun reuseActiveHardwareStream")
    assertTrue(start.contains("tryReuseActiveHardwareStreamBeforePreflight()?.let { return it }"))
    assertTrue(start.indexOf("tryReuseActiveHardwareStreamBeforePreflight") < start.indexOf("PhonePortraitLock.force"))
    assertTrue(reuse.contains("canReuseActiveHardwareStreamWithoutRootRevalidation"))
  }

  @Test
  fun recoveryStateIsGenerationGuardedAndSerializedWithPhoneMutation() {
    val recovery = body(service, "private fun scheduleTicketRecovery", "private fun onTicketRecoveryResult")
    assertTrue(service.contains("private data class TicketRecoveryRuntime("))
    assertTrue(recovery.contains("ticketRecovery.generation + 1L"))
    assertTrue(recovery.contains("serviceScope.launch(start = CoroutineStart.LAZY)"))
    assertTrue(recovery.contains("ticketRecoveryJob = job"))
    assertTrue(recovery.indexOf("ticketRecoveryJob = job") < recovery.indexOf("recoveryJob.start()"))
    assertTrue(recovery.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(recovery.contains("if (!ownsTicketRecovery(generation, runningJob))"))
    assertTrue(recovery.contains("if (ticketRecoveryJob === runningJob) ticketRecoveryJob = null"))
    assertTrue(service.contains("event.startsWith(\"recovery_\")"))
  }

  @Test
  fun latestTicketReselectIsGenerationGuardedAndCannotOverlapControlCode() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val force = body(service, "private fun forceLatestTicketReselect", "private fun markLatestTicketReselectStarted")
    val yielding = body(
      service,
      "internal fun yieldLatestTicketReselectForImmediateControl",
      "private fun recordLatestTicketReselectDeferred"
    )
    val scheduling = body(service, "private fun scheduleLatestTicketReselectRecovery", "private suspend fun runLatestTicketReselectRecovery")
    val recovery = body(service, "private suspend fun runLatestTicketReselectRecovery", "private fun latestTicketReselectGenerationIsCurrent")
    val observation = body(service, "private suspend fun observeTicketDetailForWakeWithRoot", "private suspend fun attemptWakeRecoveryActionForRootWake")
    val current = body(service, "private fun latestTicketReselectGenerationIsCurrent", "private fun markLatestTicketReselectFailed")
    val forceDispatch = commands.substringAfter("\"force_ticket_reselect\" ->").substringBefore("\"generate_control_code\" ->")
    assertTrue(forceDispatch.contains("forceLatestTicketReselect("))
    assertFalse(forceDispatch.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(force.contains("TicketLatestTicketReselectCommandPolicy.decide("))
    assertTrue(force.contains("terminal = false"))
    assertTrue(force.contains("viviStateMemory.clear"))
    assertTrue(force.contains("recordLatestTicketReselectDeferred"))
    assertTrue(yielding.contains("latestTicketReselectGeneration += 1L"))
    assertTrue(yielding.contains("latestTicketReselectStatus = \"yielded\""))
    assertTrue(yielding.contains("latestTicketReselectPhase = \"control_code_yielded\""))
    assertTrue(yielding.contains("jobsToCancel.forEach { it.cancel() }"))
    assertTrue(scheduling.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(recovery.contains("latest_ticket_reselect_in_app_reset_started"))
    assertTrue(recovery.contains("return@run runInAppUnactivatedRegistrationReset("))
    assertTrue(recovery.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertTrue(recovery.indexOf("if (requireUnactivatedRegistration)") < recovery.indexOf("recordViviHardReset"))
    assertTrue(recovery.indexOf("recordViviHardReset") < recovery.indexOf("launchViviForWake"))
    assertTrue(recovery.contains("wakeStartedAtMillis = observationStartedAtMillis"))
    assertTrue(recovery.contains("recoveryActionRepeatCooldownMillis = LATEST_TICKET_RESELECT_REPEAT_ACTION_COOLDOWN_MILLIS"))
    assertTrue(recovery.contains("LATEST_TICKET_RESELECT_IN_APP_ACTION_GRACE_MILLIS"))
    assertTrue(recovery.contains("LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS"))
    assertTrue(recovery.contains("requireNewTicketRegistration = requireUnactivatedRegistration"))
    assertTrue(recovery.contains("recordLatestTicketReselectRecoveryTelemetry(result)"))
    assertTrue(observation.contains("TicketLatestTicketReselectRecoveryPolicy.remainingMillis("))
    val inAppReset = body(service, "private suspend fun runInAppUnactivatedRegistrationReset", "private fun recordLatestTicketReselectRecoveryTelemetry")
    assertTrue(inAppReset.contains("ticketDetailReturnToListActionForHierarchy"))
    assertTrue(inAppReset.contains("bestTicketCardActionForHierarchy"))
    assertTrue(inAppReset.contains("return_to_ticket_list_for_registration"))
    assertFalse(inAppReset.contains("am force-stop"))
    assertFalse(inAppReset.contains("launchViviForWake"))
    assertTrue(observation.contains("ticketCardSelectionGraceDeadlineMillis("))
    assertTrue(observation.contains("latest_ticket_reselect_ticket_card_action_grace_started"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS = 120_000L"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_REPEAT_ACTION_COOLDOWN_MILLIS = 30_000L"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS = 60_000L"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_IN_APP_ACTION_GRACE_MILLIS = 2_500L"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_IN_APP_RESET_BUDGET_MILLIS = 8_000L"))
    assertTrue(service.contains("LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS +\n        LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS"))
    assertTrue(recovery.contains("TicketLatestTicketReselectRecoveryPolicy.finalTelemetryEvent("))
    assertFalse(recovery.contains("latest_ticket_reselect_final_state_"))
    assertFalse(recovery.contains("latest_ticket_reselect_final_action_"))
    assertTrue(current.contains("latestTicketReselectGeneration == generation"))
    assertTrue(current.contains("latestTicketReselectCommandId == commandId"))
  }

  @Test
  fun ticketRegistrationProofIsPublishedEvenWithoutAnExistingInteractionRow() {
    val publish = body(
      spacetimeWorker,
      "private suspend fun publishTicketRegistrationProof",
      "private suspend fun processTicketSliderInteraction"
    )
    val status = body(
      spacetimeWorker,
      "private suspend fun publishTicketInteractionStatus",
      "private suspend fun clearFailedTicketSliderClaim"
    )
    assertTrue(publish.contains("emptyTicketInteraction()"))
    assertTrue(status.contains("emptyTicketInteraction()"))
    assertFalse(publish.contains("ticketInteraction(config) ?: return"))
    assertTrue(spacetimeWorker.contains("internal fun emptyTicketInteraction()"))
  }

  @Test
  fun ticketSliderStartReusesCurrentProofThenChasesFingerProgress() {
    val start = body(
      service,
      "private suspend fun startTicketSliderInteraction",
      "internal suspend fun applyTicketSliderInteraction"
    )
    assertTrue(start.contains("currentTicketRegistrationProof"))
    assertTrue(start.contains("proof.streamEpoch == streamEpoch"))
    assertTrue(start.contains("sliderGeometryAgrees("))
    assertTrue(start.contains("beginTicketSliderCaptureBurst(\"ticket_slider_stroke_started\")"))
    assertTrue(start.contains("lastAppliedProgress = 0"))
    assertTrue(start.contains("continueTicketSliderGesture("))
    assertTrue(start.contains("ticketSliderTargetX(bounds, chaseProgress)"))
    assertFalse(start.contains("lastAppliedProgress = initialProgress"))
  }

  @Test
  fun ticketSliderCaptureBurstStopsWhenTheStrokeEnds() {
    val apply = body(
      service,
      "internal suspend fun applyTicketSliderInteraction",
      "private suspend fun completeTicketSliderInteraction"
    )
    val complete = body(
      service,
      "private suspend fun completeTicketSliderInteraction",
      "private fun nullResultForSlider"
    )
    assertTrue(apply.contains("endTicketSliderCaptureBurst(\"ticket_slider_stroke_failed\")"))
    assertTrue(apply.contains("endTicketSliderCaptureBurst(\"ticket_slider_stroke_ended\")"))
    assertTrue(complete.contains("endTicketSliderCaptureBurst(\"ticket_slider_completion_finished\")"))
    assertTrue(service.contains("startControlCodeRequestBurst(reason)"))
    assertTrue(service.contains("stopControlCodeRequestBurst(reason)"))
  }

  @Test
  fun latestTicketReselectCommandRemainsPendingUntilTerminalPhoneState() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandCanBePreemptedByControlCode"
    )
    val preemption = body(
      spacetimeWorker,
      "private fun commandCanBePreemptedByControlCode",
      "private fun shouldWriteRemoteCommandLog"
    )
    val deferredCheck = cycle.indexOf("if (!result.terminal)")
    val deferredReport = cycle.indexOf("maybeUpdatePhoneReport(client, desired)", deferredCheck)
    val deferredBreak = cycle.indexOf("break", deferredCheck)
    val forcedTerminalReport = cycle.indexOf("maybeUpdatePhoneReport(client, desired, force = true)")
    val acknowledgementAfterForcedReport = cycle.indexOf("client.ack(", forcedTerminalReport)
    val firstYield = cycle.indexOf("yieldLatestTicketReselectForImmediateControl")
    val commandLoop = cycle.indexOf("for (scannedCommand in commands)")

    assertTrue(spacetimeWorker.contains("val terminal: Boolean = true"))
    assertTrue(preemption.contains("commandType == \"force_ticket_reselect\""))
    assertTrue(reselectCommandPolicy.contains("commandType == \"generate_control_code\""))
    assertTrue(reselectCommandPolicy.contains("commandType == \"control_code_browser_capture\""))
    assertTrue(reselectCommandPolicy.contains("commandType == \"close_control_code\""))
    assertFalse(reselectCommandPolicy.contains("commandType == \"prepare_control_code\""))
    assertTrue(cycle.contains("TicketLatestTicketReselectPreemptionPolicy.shouldYieldFor(it.commandType)"))
    assertFalse(spacetimeWorker.contains("\"prepare_control_code\""))
    assertFalse(cycle.contains("controlCodeWorkStartedThisCycle"))
    assertTrue(deferredCheck >= 0)
    assertTrue(deferredReport > deferredCheck)
    assertTrue(deferredBreak > deferredReport)
    assertTrue(cycle.substring(deferredCheck, deferredBreak).contains("command.commandType == \"force_ticket_reselect\""))
    assertTrue(forcedTerminalReport > deferredBreak)
    assertTrue(acknowledgementAfterForcedReport > forcedTerminalReport)
    assertTrue(firstYield in 0 until commandLoop)
  }

  @Test
  fun latestTicketReselectPollingReturnsToOriginalCommandAfterInterveningWork() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandCanBePreemptedByControlCode"
    )
    val pendingCommands = body(
      spacetimeWorker,
      "suspend fun pendingCommands",
      "private fun streamCommandPriority"
    )
    val commandPriority = body(
      spacetimeWorker,
      "private fun streamCommandPriority",
      "suspend fun desiredState"
    )

    assertTrue(cycle.indexOf("var commands = if (eagerCommandLane)") < cycle.indexOf("for (scannedCommand in commands)"))
    assertTrue(cycle.contains("TicketSpacetimePollingPolicy.shouldReadPendingCommands(signal.pendingCount)"))
    assertTrue(
      cycle.indexOf("val signal = client.commandSignal(config)") <
        cycle.indexOf("drainPhoneMessages(config, client, routinePhoneMessageDrainLimit())")
    )
    assertFalse(cycle.contains("pending command post phone drain hot scan"))
    assertTrue(cycle.contains("lastInboxSignalKey = \"\""))
    assertTrue(pendingCommands.contains(".thenBy { it.createdAt }"))
    assertTrue(pendingCommands.contains(".thenBy { it.id }"))
    assertTrue(pendingCommands.contains("!ticketSpacetimeCommandExpired(row.expiresAt, now)"))
    assertTrue(commandPriority.contains("\"force_ticket_reselect\", \"reset_ticket_registration\", \"slider_control_start\" -> 3"))
  }

  @Test
  fun missingOrExpiredReselectCommandIsConfirmedAndClearsResumablePhoneState() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun reconcileLatestTicketReselectCommand"
    )
    val reconciliation = body(
      spacetimeWorker,
      "private suspend fun reconcileLatestTicketReselectCommand",
      "private fun commandCanBePreemptedByControlCode"
    )
    val exactLookup = body(
      spacetimeWorker,
      "suspend fun pendingCommandIsDispatchable",
      "private fun streamCommandPriority"
    )
    val reset = body(
      service,
      "internal fun resetLatestTicketReselectIfCommandAbsent",
      "private fun recordLatestTicketReselectDeferred"
    )

    assertTrue(cycle.contains("reconcileLatestTicketReselectCommand(config, client, commands)"))
    assertTrue(reconciliation.contains("commands.any { it.id == activeCommandId }"))
    assertTrue(reconciliation.contains("client.pendingCommandIsDispatchable(config, activeCommandId)"))
    assertTrue(reconciliation.contains("catch (error: Throwable)"))
    assertTrue(reconciliation.contains("missingLatestTicketReselectCommand.reset()"))
    assertTrue(reconciliation.contains("throw error"))
    assertTrue(reconciliation.contains("resetLatestTicketReselectIfCommandAbsent(activeCommandId)"))
    assertTrue(exactLookup.contains("WHERE id ="))
    assertTrue(exactLookup.contains("!ticketSpacetimeCommandExpired"))
    assertTrue(reset.contains("latestTicketReselectCommandId != commandId"))
    assertTrue(reset.contains("latestTicketReselectStatus = \"idle\""))
    assertTrue(reset.contains("latestTicketReselectCommandId = \"\""))
    assertTrue(reset.contains("jobsToCancel.forEach { it.cancel() }"))
  }

  @Test
  fun compactPhoneReportIncludesLatestTicketReselectProgress() {
    val compactHealth = body(
      service,
      "internal fun ticketSpacetimeCompactHealthJson",
      "internal fun peekTicketSpacetimePhoneMessages"
    )

    assertTrue(compactHealth.contains("\"latestTicketReselectStatus\""))
    assertTrue(compactHealth.contains("\"latestTicketReselectPhase\""))
    assertTrue(compactHealth.contains("\"latestTicketReselectProofSource\""))
  }

  @Test
  fun unexpectedReselectFailureBecomesTerminalButServiceCancellationCanRetryAfterRestart() {
    val scheduling = body(
      service,
      "private fun scheduleLatestTicketReselectRecovery",
      "private suspend fun runLatestTicketReselectRecovery"
    )

    assertTrue(scheduling.contains("catch (cancelled: CancellationException)"))
    assertTrue(scheduling.contains("throw cancelled"))
    assertTrue(scheduling.contains("reason = \"latest_ticket_reselect_exception\""))
    assertTrue(scheduling.contains("markLatestTicketReselectFailed("))
  }

  @Test
  fun wakeRecoveryActionsAreDeduplicatedAndDeadlineBound() {
    val observe = body(service, "private suspend fun observeTicketDetailForWakeWithRoot", "private suspend fun attemptWakeRecoveryActionForRootWake")
    val attempt = body(service, "private suspend fun attemptWakeRecoveryActionForRootWake", "private fun recoveryActionCoolingDown")
    val input = body(service, "private suspend fun runFastNonTouchInput", "private suspend fun runSensitiveFastNonTouchScript")
    assertTrue(observe.contains("sameActionCoolingDown"))
    assertTrue(observe.contains("actionRemainingMillis"))
    assertTrue(observe.contains("actionSucceeded || recoveryActionRepeatCooldownMillis > 0L"))
    assertTrue(observe.contains("timeoutMillis = minOf(NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS, actionRemainingMillis)"))
    assertTrue(attempt.contains("runFastRecoveryInput("))
    assertTrue(attempt.contains("minOf(timeoutMillis, TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS).milliseconds"))
    assertTrue(input.contains("timeout: Duration = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertTrue(input.contains("val commandTimeout = (timeout - postMillis.milliseconds).coerceAtLeast(250.milliseconds)"))
    assertTrue(input.contains("commandTimeout = commandTimeout"))
  }

  @Test
  fun recoveryTapsUseAnIndependentShortLivedRootLane() {
    val recovery = body(service, "private suspend fun runFastRecoveryInput", "private suspend fun runFastNonTouchWakeScript")
    assertTrue(service.contains("private val recoveryInputRootExecutor = SuRootExecutor()"))
    assertTrue(recovery.contains("recoveryInputRootExecutor.run(command, timeout)"))
    assertTrue(recovery.contains("TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS.milliseconds"))
    assertFalse(recovery.contains("wrapNonTouchPanelSleepClamp("))
  }

  @Test
  fun staleInactivityStopCannotTearDownAReactivatedSession() {
    val stop = body(service, "private suspend fun stopTicketSessionIfStillInactive", "private suspend fun stopTicketSessionLocked")
    assertTrue(stop.contains("viewerInputGeneration == expectedViewerInputGeneration"))
    assertTrue(stop.contains("lastViewerInputAtMillis == expectedLastInputAtMillis"))
    assertTrue(stop.contains("TicketInactivityPolicy.timedOut"))
    assertTrue(stop.contains("if (!authorized)"))
  }

  @Test
  fun staleDisconnectJobCannotStopAReplacementGeneration() {
    val disconnect = body(service, "private fun scheduleClientDisconnectGraceLocked", "private fun markViewerInput")
    assertTrue(disconnect.contains("val runningJob = coroutineContext[Job]"))
    assertTrue(disconnect.contains("clientDisconnectStopJob !== runningJob"))
    assertTrue(disconnect.contains("totalClientCount() == 0 && ticketSessionOpen()"))
  }

  @Test
  fun nonTouchPanelClampUsesOneBoundedWorkerAndPostWrites() {
    val clamp = body(service, "private fun wrapNonTouchPanelSleepClamp", "private fun shellQuote")
    assertTrue(clamp.contains("while [ ! -f"))
    assertTrue(clamp.contains("ticket_clamp_pid="))
    assertTrue(clamp.contains("touch"))
    assertTrue(clamp.contains("wait"))
    assertTrue(clamp.contains("while [") && clamp.contains("ticket_post") && clamp.contains("postWrites"))
    assertFalse(clamp.contains("ticket_background_clamp"))
  }

  @Test
  fun healthKeepsCoreStreamRecoveryAndControlSignals() {
    val health = body(service, "private fun health(): TicketStreamHealth", "private fun effectiveViviHealthForPublicStream")
    assertTrue(health.contains("streamPipelineSnapshot"))
    assertTrue(health.contains("rootHardwareH264CaptureEngine.snapshot(nowMillis)"))
    assertTrue(health.contains("lastControlCodeRequestStatus"))
    assertTrue(health.contains("lastControlCodeRequestPhases"))
    assertTrue(health.contains("lastControlCodeBrowserCaptureReason"))
    assertTrue(health.contains("ticketRecovery"))
    assertTrue(health.contains("latestTicketReselectStatus"))
  }

  @Test
  fun obsoleteCoordinatorsRuntimeStoreAndPixelTunnelStayDeleted() {
    listOf(
      "ticket/ControlCodeAutomationCoordinator.kt",
      "ticket/ControlCodeFailureLifecycle.kt",
      "ticket/TicketAutopilot.kt",
      "ticket/TicketControlCodeFastStatePolicy.kt",
      "ticket/TicketRecoveryCoordinator.kt",
      "ticket/TicketRuntimeStateStore.kt",
      "ticket/TicketScreenObserver.kt"
    ).forEach { assertFalse("retired source returned: $it", sourcePath(it) != null) }
    assertFalse(service.contains("TicketRuntimeStateStore"))
    assertFalse(service.contains("cloudflared"))
    assertFalse(service.contains("ticket-web-tunnel"))
  }

  @Test
  fun protectedRsHttpAndControlInterfacesRemain() {
    val http = body(service, "private suspend fun handleHttpClient", "private suspend fun acceptWebSocket")
    val control = body(service, "private suspend fun handleClientCommand", "private fun handleVideoClientCommand")
    listOf("/api/v1/rs/login/start", "/api/v1/rs/login/sms", "/api/v1/rs/login/status", "/api/v1/rs/login/cancel")
      .forEach { assertTrue("missing protected RS HTTP interface $it", http.contains(it)) }
    listOf("generate_rigassatiksme_qr_batch", "cancel_rigassatiksme_qr_batch", "rigassatiksme_login_start", "rigassatiksme_login_sms", "cancel_rigassatiksme_login")
      .forEach { assertTrue("missing protected RS control command $it", control.contains(it)) }
  }

  @Test
  fun protectedRsResultImageAndMonthlyTicketFlowRemain() {
    assertTrue(service.contains("handleGenerateRigasSatiksmeMonthlyTicketQr"))
    assertTrue(service.contains("runRigasSatiksmeMonthlyTicketFlow"))
    assertTrue(service.contains("captureRigasSatiksmeMonthlyTicketImageBytes"))
    assertTrue(service.contains("TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME"))
    assertTrue(service.contains("TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY"))
    assertTrue(service.contains("sendRigassatiksmeQrResult"))
  }

  private fun body(text: String, startNeedle: String, endNeedle: String): String {
    val start = text.indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = text.indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return text.substring(start, end)
  }

  private fun source(relative: String): String {
    val path = sourcePath(relative) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  private fun sourcePath(relative: String): Path? {
    val roots = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/$relative")
    )
    return roots.firstOrNull(Files::exists)
  }
}
