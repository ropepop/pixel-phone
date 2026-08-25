package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
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
  private val panelDarkLease by lazy { source("ticket/TicketActionPanelDarkLease.kt") }
  private val rootInput by lazy { source("ticket/TicketControlCodeRootInput.kt") }
  private val visualAction by lazy { source("ticket/TicketVisualAction.kt") }
  private val activationCheckpoint by lazy { source("ticket/TicketActivationCheckpoint.kt") }
  private val secureCaptureBypassOwner by lazy {
    source("ticket/TicketSecureWindowCaptureBypassOwner.kt")
  }
  private val fastOpenReadinessPolicy by lazy {
    source("ticket/TicketFastOpenVisualReadinessPolicy.kt")
  }
  private val viviEnforcer by lazy { source("ticket/TicketViviPageEnforcer.kt") }
  private val uiautomatorDump by lazy { source("ticket/TicketUiautomatorDump.kt") }
  private val viviStateMemory by lazy { source("ticket/TicketViviStateMemory.kt") }
  private val spacetimeWorker by lazy { source("ticket/TicketSpacetimeWorker.kt") }
  private val reselectCommandPolicy by lazy { source("ticket/TicketLatestTicketReselectCommandPolicy.kt") }
  private val phoneAutomationAccessibilityService by lazy {
    source("phoneautomation/PhoneAutomationAccessibilityService.kt")
  }
  private val phoneAutomationBridge by lazy { source("phoneautomation/PhoneAutomationBridge.kt") }

  @Test
  fun v3TicketActionsUseVisualProofAndCommandRevisionWithoutHierarchyReads() {
    val executor = body(
      service,
      "private suspend fun runTicketVisualActionV3",
      "private suspend fun activateTicketFromVisualAction"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun ticketVisualActivationSuccess"
    )
    assertTrue(executor.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(executor.contains("interactionRevision = command.revision"))
    assertTrue(activation.contains("command.revision"))
    assertTrue(activation.contains("recordTicketActivationDispatching"))
    assertFalse(executor.contains("TicketViviPageEnforcer"))
    assertFalse(executor.contains("observeRootViviState"))
    assertTrue(executor.contains("observation.backBounds"))
    assertFalse(executor.contains("input keyevent KEYCODE_BACK"))
    assertFalse(activation.contains("TicketViviPageEnforcer"))
    assertTrue(activation.indexOf("rememberTicketVisualActivatedAnchor(activated)") <
      activation.indexOf("recordTicketActivationProven(dispatching, activationRevision)"))
    val checkpointGate = executor.indexOf("ticketActivationCheckpoint(command.id, checkpointRevision, request.attemptId)")
    val navigationLoop = executor.indexOf("while (mutations < TICKET_ACTION_V3_MAX_NAVIGATION_MUTATIONS)")
    val navigationTap = executor.indexOf("tapTicketVisualProbeBounds")
    assertTrue(checkpointGate >= 0)
    assertTrue(checkpointGate < navigationLoop)
    assertTrue(checkpointGate < navigationTap)
  }

  @Test
  fun nativeEdgeCropIsSharedByEncodingMotionVisibilityAndVisualClassification() {
    listOf(
      "TICKET_MEDIA_LEFT_CROP_SOURCE_PIXELS = 4",
      "TICKET_MEDIA_RIGHT_CROP_SOURCE_PIXELS = 3",
      "TICKET_MEDIA_BOTTOM_CROP_SOURCE_PIXELS = 3"
    ).forEach { assertTrue(config.contains(it)) }
    listOf(
      "--crop-left-source",
      "--crop-top-source",
      "--crop-right-source",
      "--crop-bottom-source"
    ).forEach { assertTrue(h264Engine.contains(it)) }
    assertTrue(h264Main.contains("Rect sourceCrop = sourceCropRect("))
    assertTrue(h264Main.contains("MotionSample sample = motionSampler.sample(source.bitmap, sourceCrop)"))
    assertTrue(h264Main.contains("visible = frameLooksVisible(source.bitmap, sourceCrop)"))
    assertTrue(h264Main.contains("classifyControlCodeVisualState(\n              source.bitmap,\n              sourceCrop,"))
    assertTrue(h264Main.contains("drawBitmap(inputSurface, source.bitmap, sourceCrop, destination, paint)"))
    assertEquals(2, Regex("Rect sourceCrop = sourceCropRect\\(").findAll(h264Main).count())
    assertEquals(1, Regex("return new Rect\\(").findAll(h264Main).count())
  }

  @Test
  fun proveCurrentUsesTwoFreshVisualFramesWithoutLaunchingOrNavigating() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    val proof = body(
      service,
      "private suspend fun proveCurrentTicketVisualAction",
      "private suspend fun activateTicketFromVisualAction"
    )
    val proveBranch = action.substring(
      action.indexOf("if (request.target == TicketVisualActionTarget.PROVE_CURRENT)"),
      action.indexOf("if (!panelLease.beforeMutationAllowed())")
    )
    assertTrue(proveBranch.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(proveBranch.contains("currentOnly = true"))
    assertTrue(h264Engine.contains("requestTicketCurrentVisualProbe"))
    assertTrue(h264Main.contains("TicketVisualActionClassifier.classifyCurrent(pixels)"))
    assertTrue(h264Main.contains("TicketVisualActionClassifier.currentVisualDiagnostic(pixels)"))
    assertTrue(proveBranch.contains("return proveCurrentTicketVisualAction"))
    assertFalse(proveBranch.contains("launchViviForWake"))
    assertFalse(proveBranch.contains("tapTicketVisualProbeBounds"))
    assertTrue(proof.contains("TicketVisualPhoneState.UNACTIVATED_DETAIL"))
    assertTrue(proof.contains("ticket_action_current_unactivated_proved"))
    assertTrue(proof.contains("interactionRevision = command.revision"))
    assertFalse(proof.contains("selectedAnchor"))
    assertFalse(proof.contains("launchViviForWake"))
    assertFalse(proof.contains("tapTicketVisualProbeBounds"))
  }

  @Test
  fun v3ActionClampCoversExecutionAndCleansUpOnEveryExit() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    assertTrue(wrapper.contains("TicketActionPanelDarkLease("))
    assertTrue(wrapper.contains("ownerProcessId = android.os.Process.myPid()"))
    assertTrue(wrapper.indexOf("panelLease.acquire()") <
      wrapper.indexOf("runTicketVisualActionV3WithCaptureLease("))
    assertTrue(wrapper.contains("finally"))
    assertTrue(wrapper.contains("withContext(NonCancellable)"))
    assertTrue(wrapper.contains("panelLease.release(\"ticket_action_terminal\")"))
    assertTrue(wrapper.contains("ticket_action_physical_touch_preempted_after_dispatch"))
    assertTrue(panelDarkLease.contains("physical_touch_at_mutation_boundary"))
    assertTrue(panelDarkLease.contains("/proc/\${'$'}owner_pid/stat"))
    assertTrue(panelDarkLease.contains("CLEANUP_STALE_HELPERS_SCRIPT"))
  }

  @Test
  fun controlCodeOwnsTheRawPanelDarkLeaseBeforeKeyboardOrPhoneMutation() {
    val request = body(
      service,
      "private suspend fun handleGenerateControlCode(",
      "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr"
    )
    val lane = request.substringAfter("controlCodePhoneMutationLane.withOwnership {")
    assertTrue(lane.contains("TicketActionPanelDarkLease("))
    assertTrue(lane.contains("panelDarkLease?.acquire()"))
    assertTrue(lane.indexOf("panelDarkLease?.acquire()") <
      lane.indexOf("RequestScopedKeyboardClampLease("))
    assertTrue(lane.indexOf("panelDarkLease?.acquire()") <
      lane.indexOf("ensureControlCodeRequestPreflight("))
    assertTrue(request.contains("withContext(NonCancellable)"))
    assertTrue(request.contains("keyboardClampLease?.release(\"control_code_request_finally\")"))
    assertTrue(request.contains("panelDarkLease?.release(\"control_code_terminal\")"))
  }

  @Test
  fun physicalTouchAfterRegistrationStartsIsVisuallyReconciledWithoutAnotherGesture() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun reconcileTicketRegistrationAfterUncertainDispatch"
    )
    val reconciliation = body(
      service,
      "private suspend fun reconcileTicketRegistrationAfterUncertainDispatch",
      "private suspend fun ticketVisualActivationSuccess"
    )
    val start = activation.indexOf("startTicketSliderGesture(")
    val firstReconciliation = activation.indexOf(
      "return reconcileTicketRegistrationAfterUncertainDispatch(",
      start
    )
    val end = activation.indexOf("endTicketSliderGesture(", start)
    assertTrue(start >= 0)
    assertTrue(firstReconciliation > start)
    assertTrue(firstReconciliation < end)
    assertTrue(Regex("reconcileTicketRegistrationAfterUncertainDispatch\\(")
      .findAll(activation).count() >= 3)
    assertTrue(reconciliation.contains("awaitStableTicketVisualActionObservation("))
    assertTrue(reconciliation.contains("allowUnknown = true"))
    assertTrue(reconciliation.contains("recordTicketActivationProven(checkpoint, activationRevision)"))
    assertTrue(reconciliation.contains("ticketActivationCheckpointStore.recordNeedsAttention(checkpoint)"))
    assertTrue(reconciliation.contains("status = \"needs_attention\""))
    assertTrue(reconciliation.contains("reasonPrefix: String = \"ticket_action_physical_touch_preempted_after_dispatch\""))
    assertTrue(reconciliation.contains("if (reconciled == null) \"visual_unproved\" else \"reconciled\""))
    assertFalse(reconciliation.contains("startTicketSliderGesture("))
    assertFalse(reconciliation.contains("endTicketSliderGesture("))
    assertFalse(reconciliation.contains("retryTicketSliderFullStroke("))
    assertFalse(reconciliation.contains("tapTicketVisualProbeBounds("))
    assertTrue(wrapper.contains("result.reason.startsWith(\"ticket_action_physical_touch_preempted_after_dispatch_\")"))
    assertTrue(wrapper.contains("reconcileTicketRegistrationAfterUncertainDispatch("))
    assertTrue(wrapper.contains("ticketVisualSwitchAnchors.recentActivatedAnchor"))
    val unknownStart = activation.substringAfter("TicketSliderGestureStartResult.UNKNOWN ->")
      .substringBefore("TicketSliderGestureStartResult.REJECTED ->")
    assertTrue(unknownStart.contains("reconcileTicketRegistrationAfterUncertainDispatch("))
    assertTrue(unknownStart.contains("reasonPrefix = \"ticket_action_gesture_start_uncertain\""))
    val uncertainEnd = activation.substringAfter("if (!ended) {")
      .substringBefore("val activationObservation")
    assertTrue(uncertainEnd.contains("reconcileTicketRegistrationAfterUncertainDispatch("))
    assertTrue(uncertainEnd.contains("reasonPrefix = \"ticket_action_gesture_completion_uncertain\""))
    assertFalse(reconciliation.contains("stableObservation"))
  }

  @Test
  fun startupFinishesStaleClampCleanupBeforeSubscribingToTicketActions() {
    val startup = body(service, "override fun onCreate()", "override fun onStartCommand")
    val reconcile = startup.indexOf("disableSecureWindowCaptureBypass(\"service_startup_reconcile\")")
    val helperReadiness = startup.indexOf("startRootHardwareH264StartupReadiness()")
    val cleanup = startup.indexOf("TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT")
    val worker = startup.indexOf("TicketSpacetimeWorker(")
    val workerStart = startup.indexOf("worker.start()")
    assertTrue(reconcile >= 0)
    assertTrue(helperReadiness > reconcile)
    assertTrue(cleanup > helperReadiness)
    assertTrue(worker > cleanup)
    assertTrue(workerStart > worker)
    assertTrue(startup.contains("ticketActionPanelDarkLeaseFailures.incrementAndGet()"))
    val cleanupFailure = startup.substringAfter("if (!staleClampCleanup.ok) {")
      .substringBefore("} else if")
    assertTrue(cleanupFailure.contains("return@launch"))
    assertTrue(service.contains(
      "private val ticketActionPanelDarkVerifyRootExecutor = TicketRootCommandWorker()"
    ))
    assertTrue(startup.contains("ticketActionPanelDarkVerifyRootExecutor.runScript("))
    val destroy = body(service, "override fun onDestroy()", "private fun startServer")
    assertTrue(destroy.contains("ticketActionPanelDarkVerifyRootExecutor.close()"))
  }

  @Test
  fun startupReconciliationGatesEverySessionAndShutdownQuiescesBeforeFinalRelease() {
    val startup = body(service, "override fun onCreate()", "override fun onStartCommand")
    val startCommand = body(service, "override fun onStartCommand", "override fun onBind")
    val startSession = body(
      service,
      "private suspend fun startTicketSession(",
      "private suspend fun startTicketSessionLocked"
    )
    val destroy = body(service, "override fun onDestroy()", "private fun startServer")

    assertTrue(startup.indexOf("disableSecureWindowCaptureBypass(\"service_startup_reconcile\")") <
      startup.indexOf("startRootHardwareH264StartupReadiness()"))
    assertTrue(startup.indexOf("disableSecureWindowCaptureBypass(\"service_startup_reconcile\")") <
      startup.indexOf("TicketSpacetimeWorker("))
    assertTrue(startSession.indexOf("secureCaptureStartupReconciled.await()") <
      startSession.indexOf("sessionMutex.withLock"))
    assertTrue(startSession.contains("if (serviceLifecycleStopping)"))
    assertTrue(startCommand.indexOf("secureWindowCaptureBypassOwner.stopAcceptingNewOwnership()") <
      startCommand.indexOf("stopLocalServer()"))

    val fence = destroy.indexOf("secureWindowCaptureBypassOwner.stopAcceptingNewOwnership()")
    val inactive = destroy.indexOf("streamActive = false")
    val cancelAction = destroy.indexOf("ticketActionV3Job?.cancel()")
    val cancelScope = destroy.indexOf("serviceJob.cancel()")
    val joinScope = destroy.indexOf("serviceJob.join()")
    val finalRelease = destroy.indexOf("disableSecureWindowCaptureBypass(\"service_destroyed_final\")")
    assertTrue(fence >= 0)
    assertTrue(inactive > fence)
    assertTrue(cancelAction > inactive)
    assertTrue(cancelScope > cancelAction)
    assertTrue(joinScope > cancelScope)
    assertTrue(finalRelease > joinScope)
  }

  @Test
  fun duplicateStartReusesEveryKnownViewWithoutLegacyNavigation() {
    val start = body(
      service,
      "private suspend fun startTicketSessionLocked",
      "private fun tryReuseActiveHardwareStreamBeforePreflight"
    )
    val validation = body(
      service,
      "private suspend fun validateActiveTicketSessionBeforeReuse",
      "private suspend fun refreshHardwareReliabilityIfProbePasses"
    )
    val unprovedBranch = start.substringAfter(
      "if (!validateActiveTicketSessionBeforeReuse(\"session_start_already_active\"))"
    ).substringBefore("return@session reuseActiveHardwareStream(")

    assertTrue(validation.contains("TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(result.state)"))
    assertFalse(validation.contains("observeRootViviState("))
    assertFalse(unprovedBranch.contains("scheduleTicketRecovery("))
    assertTrue(unprovedBranch.contains("no navigation was attempted"))
    assertTrue(unprovedBranch.contains("ok = false"))
  }

  @Test
  fun detachAndStopNeverReportCleanWhenSecureCaptureReleaseIsUnproved() {
    val detach = body(service, "private suspend fun noteClientDetachedLocked", "private suspend fun stopTicketSessionIfAllowed")
    val stop = body(service, "private suspend fun stopTicketSessionLocked", "private suspend fun completeTicketSessionStop")
    val release = body(
      service,
      "private suspend fun disableSecureWindowCaptureBypass(",
      "private suspend fun enableNotificationLockdown"
    )

    assertTrue(release.contains("if (!first.ok && first.cleanupRequired)"))
    assertTrue(release.contains("secureWindowCaptureBypassOwner.release(\"retry:${'$'}reason\")"))
    assertTrue(detach.contains("if (!bypassRelease.ok)"))
    assertTrue(detach.contains("ok = bypassRelease.ok"))
    assertTrue(stop.contains("if (bypassRelease.ok) \"stopped\" else \"needs_attention\""))
    assertTrue(stop.contains("session_stop_cleanup_unproved"))
  }

  @Test
  fun protectedPixelCaptureOwnsAndVerifiesBypassBeforeEveryColdProbe() {
    val sessionStart = body(
      service,
      "private suspend fun startTicketSessionLocked",
      "private fun tryReuseActiveHardwareStreamBeforePreflight"
    )
    val sessionBypass = sessionStart.indexOf(
      "val captureLease = ensureSecureWindowCaptureBypassForProtectedPixels(\"session_start\")"
    )
    assertTrue(sessionBypass >= 0)
    assertTrue(sessionBypass < sessionStart.indexOf("rootHardwareH264CaptureEngine.snapshot()"))
    assertTrue(sessionBypass < sessionStart.indexOf("awaitRootHardwareH264StartupReadiness"))
    val sessionFailure = sessionStart
      .substringAfter("if (captureLease == null)")
      .substringBefore("if (streamActive)")
    assertTrue(sessionFailure.contains("state = \"secure_capture_bypass_unavailable\""))
    assertTrue(sessionFailure.contains("ok = false"))

    val recovery = body(
      service,
      "private suspend fun recoverTicketSpacetimeDesiredStream",
      "private fun forceLatestTicketReselect"
    )
    val recoveryBypass = recovery.indexOf(
      "val captureLease = ensureSecureWindowCaptureBypassForProtectedPixels(\"spacetime_desired_recovery\")"
    )
    assertTrue(recoveryBypass >= 0)
    assertTrue(recoveryBypass > recovery.indexOf("rootHardwareH264CaptureEngine.cleanupStaleProcesses()"))
    assertTrue(recoveryBypass < recovery.indexOf("rootHardwareH264CaptureEngine.probe(sourceSize.first, sourceSize.second)"))
    val recoveryFailure = recovery
      .substringAfter("if (captureLease == null)")
      .substringBefore("rootH264BlankProbeJob?.cancel()")
    assertTrue(recoveryFailure.contains("secure_capture_bypass_unavailable"))
    assertTrue(recoveryFailure.contains("ok = false"))
    assertTrue(recovery.contains("secureWindowCaptureBypassOwner.runRetainingOnSuccess("))
    assertTrue(recovery.contains("lease = captureLease"))
    val preparation = body(
      service,
      "private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership",
      "private suspend fun verifyRootHardwareSecureCapturePixelsVisible"
    )
    assertTrue(preparation.contains("secureWindowCaptureBypassOwner.runRetainingOnSuccess("))
    assertTrue(preparation.contains("secureWindowCaptureBypassOwner.currentLease()"))
    assertTrue(preparation.contains("lease = captureLease"))
    assertTrue(preparation.contains("it && streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(preparation.contains("retainIfCurrent = {"))
  }

  @Test
  fun secureCaptureBypassUsesLiveReadbackBoundedWorkersAndProvedDualRestore() {
    val ensure = body(
      secureCaptureBypassOwner,
      "suspend fun ensure(reason: String)",
      "suspend fun release(reason: String)"
    )
    assertTrue(ensure.indexOf("var before = readbackUnlocked()") < ensure.indexOf("if (before.liveActive"))
    assertFalse(ensure.contains("if (state.active)"))
    assertTrue(ensure.indexOf("ESTABLISH_ACTIVE_OWNERSHIP_SCRIPT") <
      ensure.indexOf("if (before.liveActive) {"))
    assertTrue(ensure.contains("withContext(NonCancellable)"))
    assertTrue(secureCaptureBypassOwner.contains("primaryRootExecutor.runScript(script, commandTimeout)"))
    assertTrue(secureCaptureBypassOwner.contains("fallbackRootExecutor.runScript(script, commandTimeout)"))
    assertFalse(secureCaptureBypassOwner.contains("ProcessBuilder"))
    assertTrue(secureCaptureBypassOwner.contains("val secureAttempt = runRestoreOperation("))
    assertTrue(secureCaptureBypassOwner.contains("val debuggableAttempt = runRestoreOperation("))
    assertTrue(secureCaptureBypassOwner.contains("val bothProved = secureProved && debuggableProved"))
    assertTrue(secureCaptureBypassOwner.contains("if (bothProved) runRestoreOperation(CLEAR_SAVED_STATE_SCRIPT)"))
    assertTrue(secureCaptureBypassOwner.contains("if (activeLease != lease)"))
    assertTrue(secureCaptureBypassOwner.contains("stopAcceptingNewOwnership"))
    assertTrue(service.contains("secureWindowCaptureBypassFallbackRootExecutor.close()"))
  }

  @Test
  fun v3ActionStopsBeforeViviMutationWhenColdStreamStartFails() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    val start = action.indexOf("val startResponse = startTicketSession()")
    val gate = action.indexOf("if (!startResponse.ok || !streamActive")
    val unavailable = action.indexOf("ticket_action_visual_stream_unavailable")
    val dispatched = action.indexOf("panelLease.markMutationMayHaveDispatched()", start)
    val launch = action.indexOf("launchViviForWake", start)
    assertTrue(start >= 0)
    assertTrue(gate > start)
    assertTrue(unavailable > gate)
    assertTrue(dispatched > unavailable)
    assertTrue(launch > dispatched)
  }

  @Test
  fun ticketHierarchyAuthorityIsGloballyDisconnectedWhileRsKeepsItsDump() {
    val ordinaryObservation = body(
      service,
      "private suspend fun observeRootViviState(",
      "private suspend fun controlExitHierarchy"
    )
    val wakeObservation = body(
      service,
      "private suspend fun observeRootViviStateForWake(",
      "private suspend fun observeTicketDetailForWakeWithRoot"
    )
    val retiredDump = body(
      service,
      "private suspend fun dumpViviHierarchy(",
      "private fun recordRootReadiness"
    )
    val retiredWakeDump = body(
      service,
      "private suspend fun dumpViviHierarchyForWake(",
      "private suspend fun observeRootViviStateForWake"
    )

    assertTrue(ordinaryObservation.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(wakeObservation.contains("awaitStableTicketVisualActionObservation"))
    assertFalse(ordinaryObservation.contains("TicketViviPageEnforcer"))
    assertFalse(wakeObservation.contains("TicketViviPageEnforcer"))
    assertTrue(retiredDump.contains("ticket_hierarchy_detection_retired"))
    assertTrue(retiredWakeDump.contains("ticket_hierarchy_detection_retired"))
    assertFalse(retiredDump.contains("runScript"))
    assertFalse(retiredWakeDump.contains("runScript"))
    assertFalse(service.contains("snapshotTicketRegistrationNodes"))

    assertTrue(uiautomatorDump.contains("fun commandForRigasSatiksme"))
    assertTrue(uiautomatorDump.contains("/data/local/tmp/rs-direct-window.xml"))
    assertFalse(uiautomatorDump.contains("/sdcard/pixel-ticket-window.xml"))
    assertFalse(uiautomatorDump.contains("pixel-vivi-fast-return-window.xml"))
    assertTrue(service.contains("TicketUiautomatorDump.commandForRigasSatiksme"))
    assertFalse(viviStateMemory.contains("lastRootTicketDetailSnapshot"))
    assertTrue(viviStateMemory.contains("Ticket hierarchy bytes are never retained"))
  }

  @Test
  fun publicV3FailureReasonIsAFixedSafeToken() {
    val handler = body(service, "private fun handleTicketVisualActionV3", "private suspend fun runTicketVisualActionV3")
    assertTrue(handler.contains("ticket_action_v3_internal_failure"))
    assertFalse(handler.contains("safeErrorDetail(error)"))
    assertFalse(handler.contains("error.message"))
  }

  @Test
  fun terminalV3JournalClearsOnlyAfterDurableAck() {
    val terminal = body(service, "private fun ticketVisualActionTerminal", "private fun persistTicketVisualTerminalSnapshot")
    val poll = body(spacetimeWorker, "private suspend fun runCycle", "private fun commandUsesLegacyRegistrationInteraction")
    assertTrue(terminal.contains("persistTicketVisualTerminalSnapshot"))
    assertFalse(terminal.contains("clearTicketVisualActionJournal"))
    val ack = poll.indexOf("client.ack(")
    val clear = poll.indexOf("service.acknowledgeTicketVisualActionV3")
    assertTrue(ack >= 0)
    assertTrue(clear > ack)
  }

  @Test
  fun v3JournalPersistsTransitionDirectionAndPrivateAnchor() {
    val load = body(
      service,
      "private fun loadTicketVisualActionJournal",
      "private fun persistTicketVisualActionJournal"
    )
    val persist = body(
      service,
      "private fun persistTicketVisualActionJournal",
      "private fun clearTicketVisualActionJournal"
    )
    listOf(
      "navigation_from_state",
      "navigation_to_state",
      "navigation_anchor",
      "stream_epoch",
      "frame_sequence",
      "slider_left_basis_points",
      "slider_top_basis_points",
      "slider_right_basis_points",
      "slider_bottom_basis_points"
    ).forEach { key ->
      assertTrue(load.contains(key))
      assertTrue(persist.contains(key))
    }
    assertTrue(persist.contains("return ticketVisualActionJournalWriteProved("))
    assertTrue(persist.contains("readBack = ::loadTicketVisualActionJournal"))
    assertTrue(visualAction.contains("if (!commit()) return false"))
    assertTrue(visualAction.contains("return readBack() == value"))
  }

  @Test
  fun everySuccessfulV3TerminalUsesARealEncodedFrameWatermark() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    val activation = body(
      service,
      "private suspend fun ticketVisualActivationSuccess",
      "private fun rememberTicketVisualActivatedAnchor"
    )
    val success = body(
      service,
      "private suspend fun ticketVisualActionSuccess",
      "private fun ticketVisualActionTerminal"
    )
    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )
    assertFalse(action.contains("ticketVisualActionTerminal(request, true"))
    assertTrue(action.contains("ticketVisualActionSuccess("))
    assertTrue(activation.contains("awaitTicketVisualActionFrameWatermark"))
    assertTrue(activation.contains("proofWatermark = watermark"))
    assertTrue(success.contains("awaitTicketVisualActionFrameWatermark"))
    assertTrue(success.contains("bindTicketRegistrationProofToCurrentWatermark"))
    assertTrue(terminal.contains("successfulWatermarkCurrent"))
    assertTrue(terminal.contains("ticket_action_frame_watermark_unproved"))
    assertTrue(terminal.contains("val terminalSwitchAvailable = terminalOk && switchAvailable"))
    assertTrue(terminal.contains("switchAvailable = terminalSwitchAvailable"))
  }

  @Test
  fun terminalReducerPrecedesSliderGeometryAndTheJournalRetainsTheRetryPayload() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    val terminalUpdate = cycle.indexOf("client.updateTicketActionV3(config, action)")
    val geometryUpdate = cycle.indexOf("client.updateTicketSliderRegionV3(config, region)")
    assertTrue(terminalUpdate >= 0)
    assertTrue(geometryUpdate > terminalUpdate)

    val success = body(
      service,
      "private suspend fun ticketVisualActionSuccess",
      "private fun ticketVisualActionTerminal"
    )
    val persist = body(
      service,
      "private fun persistTicketVisualTerminalSnapshot",
      "private fun loadTicketVisualActionJournal"
    )
    assertTrue(success.contains("sliderRegion = sliderRegion"))
    assertTrue(success.contains("Terminal proof and normalized geometry enter the local journal atomically"))
    assertTrue(persist.contains("snapshot.sliderRegion?.leftBasisPoints"))
    assertTrue(persist.contains("snapshot.sliderRegion?.bottomBasisPoints"))
  }

  @Test
  fun v3NavigationObservesOneDispatchedTapInsteadOfRetryingAnUncertainResult() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    val tap = action.indexOf("tapTicketVisualProbeBounds(tapBounds")
    val observe = action.indexOf("ticket_action_v3_after_navigation_\$mutations")
    val convergence = action.indexOf(
      "convergenceExtensionMillis = TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS"
    )
    val reconcile = action.indexOf("ticketVisualJournalReconciled(navigationJournal")
    assertTrue(tap >= 0)
    assertTrue(observe > tap)
    assertTrue(convergence > observe)
    assertTrue(reconcile > convergence)
    assertEquals(1, Regex("tapTicketVisualProbeBounds\\(tapBounds").findAll(action).count())
    assertFalse(action.contains("ticket_action_v3_final_convergence_\$mutations"))
    assertFalse(action.contains("if (!tapTicketVisualProbeBounds(tapBounds"))
  }

  @Test
  fun visualConvergenceKeepsConsensusWhileRecoveringAtTheFiveSecondBoundary() {
    val wait = body(
      service,
      "private suspend fun awaitStableTicketVisualActionObservation",
      "private suspend fun awaitTicketVisualActionFrameWatermark"
    )
    val boundary = wait.indexOf("if (!convergenceExtended)")
    val extendDeadline = wait.indexOf("deadline += boundedConvergenceExtensionMillis")
    val recover = wait.indexOf("val recoverAtBoundary")
    val offer = wait.indexOf("consensus.offer(current, allowUnknown)")

    assertTrue(boundary >= 0)
    assertTrue(extendDeadline > boundary)
    assertTrue(recover > extendDeadline)
    assertTrue(offer > recover)
    assertTrue(wait.contains("if (recoverAtBoundary)"))
    assertTrue(wait.contains("beforeProbeStreamEpoch != consensusStreamEpoch"))
    assertTrue(wait.contains("afterProbeStreamEpoch != beforeProbeStreamEpoch"))
  }

  @Test
  fun v3ListNavigationUsesTargetSpecificProvedGeometry() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    assertTrue(action.contains("selectedCard?.navigationBoundsFor(request.target)"))
    assertFalse(action.contains("selectedCard?.bounds"))
    assertTrue(visualAction.contains("TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> activatedDetailBounds"))
    assertTrue(visualAction.contains("TicketVisualActionTarget.REDETECT_LATEST -> registrationBounds"))
  }

  @Test
  fun activatedDetailAndControlCodeNavigationNeverTapTheGenericCardBody() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val canonicalize = body(
      service,
      "private suspend fun canonicalizeControlCodeTicketDetail",
      "private suspend fun awaitStableControlCodeRawVisualSignature"
    )
    val inline = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    assertTrue(canonicalize.contains("activatedCard.activatedDetailBounds"))
    assertFalse(canonicalize.contains("activatedCard.bounds"))
    assertTrue(inline.contains("?.activatedDetailBounds"))
    assertFalse(inline.contains("latestCard()?.bounds"))
    assertTrue(action.contains("observation.activatedCardForRecentDetail(ticketVisualSwitchAnchors)"))
    assertTrue(action.contains("ticketVisualObservationAfterRecentActivatedSelection("))
    assertFalse(action.contains(
      "TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,\n" +
        "            TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED ->\n" +
        "              observation.cardFor(request.target, ticketVisualSwitchAnchors)"
    ))
  }

  @Test
  fun v3HomeRecoveryUsesOneJournaledVisualTicketTabTapAndFailsClosedWhenUnproved() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val journalPersist = action.indexOf("persistTicketVisualActionJournal(navigationJournal)")
    val physicalDispatch = action.indexOf(
      "tapTicketVisualProbeBounds(tapBounds, \"ticket_action_v3_\${request.target.wireName}\")"
    )
    val reconciliation = action.indexOf(
      "if (!ticketVisualJournalReconciled(navigationJournal, request, observation))"
    )
    assertTrue(action.contains(
      "TicketVisualPhoneState.VIVI_HOME -> observation.ticketsTabBounds"
    ))
    assertTrue(journalPersist >= 0)
    assertTrue(physicalDispatch > journalPersist)
    val journalFailureGate = action.indexOf("if (!persistTicketVisualActionJournal(navigationJournal))")
    assertTrue(journalFailureGate >= 0)
    assertTrue(journalFailureGate < physicalDispatch)
    assertTrue(action.substring(journalFailureGate, physicalDispatch).contains(
      "ticket_action_navigation_journal_unproved"
    ))
    assertTrue(reconciliation > physicalDispatch)
    assertTrue(action.substring(reconciliation).contains(
      "ticket_action_navigation_dispatch_uncertain"
    ))
    assertFalse(action.contains("UiAutomator"))
    assertFalse(action.contains("dumpViviHierarchy"))
    assertTrue(service.contains(
      "ticket-stream-2026-08-25-native-edge-action-clamp-proof-v322"
    ))
    assertFalse(service.contains(
      "ticket-stream-2026-08-25-native-edge-action-clamp-proof-v320"
    ))
    val afterNavigation = action.substring(
      action.indexOf("val postNavigationObservation"),
      action.indexOf("observation = postNavigationObservation")
    )
    assertTrue(afterNavigation.contains(
      "convergenceExtensionMillis = TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS"
    ))
    assertEquals(1, Regex("awaitStableTicketVisualActionObservation\\(")
      .findAll(afterNavigation).count())
    assertFalse(afterNavigation.contains("ticket_action_v3_final_convergence"))
  }

  @Test
  fun physicalActivationRequiresProvedDispatchCheckpointBeforeHeldGesture() {
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun reconcileTicketRegistrationAfterUncertainDispatch"
    )
    val dispatchWrite = activation.indexOf("val dispatching = recordTicketActivationDispatching(checkpoint)")
    val dispatchFailure = activation.indexOf("ticket_action_activation_dispatch_checkpoint_unproved")
    val mutationBoundary = activation.indexOf("panelLease.markMutationMayHaveDispatched()")
    val heldGesture = activation.indexOf("PhoneAutomationServiceBridge.startTicketSliderGesture")

    assertTrue(dispatchWrite >= 0)
    assertTrue(dispatchFailure > dispatchWrite)
    assertTrue(mutationBoundary > dispatchFailure)
    assertTrue(heldGesture > mutationBoundary)
    assertTrue(activationCheckpoint.contains("fun save(checkpoint: TicketActivationCheckpoint): Boolean"))
    assertTrue(activationCheckpoint.contains("if (!backend.save(checkpoint)) return null"))
    assertTrue(activationCheckpoint.contains("backend.load() == checkpoint"))
    assertTrue(activationCheckpoint.contains(".commit()"))
  }

  @Test
  fun controlCodeCleanupNeverPromotesTheViviRouteRootToTicketDetailSuccess() {
    val prepare = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    val cleanup = body(
      service,
      "private suspend fun runControlExitCleanup",
      "private suspend fun completeControlExitCleanup"
    )
    val homeBranch = prepare.substringAfter("TicketVisualPhoneState.VIVI_HOME ->")
      .substringBefore("TicketVisualPhoneState.TICKET_LIST")
    assertTrue(homeBranch.contains("vivi_home_requires_ticket_action"))
    assertTrue(homeBranch.contains("return null"))
    assertTrue(cleanup.contains("if (!preparedHierarchy.isNullOrBlank())"))
    assertTrue(cleanup.contains("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION"))
    assertTrue(cleanup.contains("return false"))
  }

  @Test
  fun redetectionConvergesOnUnactivatedDetailInsteadOfPublishingTheTicketList() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    assertTrue(action.contains(
      "TicketVisualActionTarget.REDETECT_LATEST -> TicketVisualPhoneState.UNACTIVATED_DETAIL"
    ))
    assertTrue(action.contains("TicketVisualActionTarget.REDETECT_LATEST -> observation.latestRegistrationCard()"))
    assertTrue(action.contains("ticket_action_latest_redetected"))
    assertFalse(service.contains("redetectListIdentityOnly"))
    assertFalse(action.contains("observation.state == TicketVisualPhoneState.TICKET_LIST\n      ) break"))
    assertFalse(action.contains("return ticketVisualActionSuccess(request, \"ticket_action_latest_redetected\", observation)"))
  }

  @Test
  fun spacetimeOwnsSwitchExpiryAndActivationRefreshScheduling() {
    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )
    assertTrue(visualAction.contains("val policyRevision: String"))
    assertTrue(visualAction.contains("val switchExpiresAt: String"))
    assertTrue(terminal.contains("request.hasSpacetimeSwitchAuthority"))
    assertTrue(terminal.contains("request.switchExpiresAt"))
    assertFalse(service.contains("TICKET_ACTION_V3_SWITCH_WINDOW_MILLIS"))
    assertFalse(visualAction.contains("recentActivatedAtMillis"))
    assertFalse(visualAction.contains("latestUnactivatedAtMillis"))
    assertFalse(service.contains("putLong(\"recent_activated_at\""))
    assertFalse(service.contains("putLong(\"latest_unactivated_at\""))
    assertFalse(spacetimeWorker.contains("ensurePendingTicketActivationSchedule"))
    assertFalse(spacetimeWorker.contains("ticketremote_schedule_activation_expiry_reset"))
    assertTrue(spacetimeWorker.contains("client.commitTicketActivation("))
  }

  @Test
  fun subscribedTerminalV3WaitsForPollCommitBeforePublicTerminalUpdate() {
    val subscribed = body(
      spacetimeWorker,
      "private suspend fun handleSubscribedTicketCommand",
      "private suspend fun handleKeyframeCommand"
    )
    val terminalGuardIndex = subscribed.indexOf("takeIf { !result.terminal }")
    val subscriptionUpdateIndex = subscribed.indexOf("client.updateTicketActionV3(config, action)")
    assertTrue(terminalGuardIndex >= 0)
    assertTrue(subscriptionUpdateIndex > terminalGuardIndex)
    assertTrue(subscribed.contains("subscribedCommandHandoff.fromSubscription(command.id)"))
    assertFalse(subscribed.contains("subscribedCommandResults.record"))
    assertFalse(subscribed.contains("client.ack("))
    val poll = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    val commitIndex = poll.indexOf("client.commitTicketActivation(")
    val updateIndex = poll.indexOf("client.updateTicketActionV3(config, action)", commitIndex)
    assertTrue(commitIndex >= 0)
    assertTrue(updateIndex > commitIndex)
  }

  @Test
  fun v3LegacyInteractionPublicationsStayBehindTheNarrowCommandPolicy() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    val classifier = body(
      spacetimeWorker,
      "private fun commandUsesLegacyRegistrationInteraction",
      "private suspend fun publishTicketInteractionStatus"
    )
    val preparingGate = cycle.indexOf("usesLegacyRegistrationInteraction &&")
    val preparingPublication = cycle.indexOf("publishTicketInteractionStatus(", preparingGate)
    val terminalGate = cycle.indexOf("if (usesLegacyRegistrationInteraction && result.terminal)")
    val terminalProofPublication = cycle.indexOf("publishTicketRegistrationProof(", terminalGate)
    val terminalFailurePublication = cycle.indexOf("publishTicketInteractionStatus(", terminalGate)

    assertTrue(cycle.contains("val usesLegacyRegistrationInteraction = commandUsesLegacyRegistrationInteraction(command)"))
    assertTrue(cycle.contains("command.commandType != \"ticket_action_v3\" || usesLegacyRegistrationInteraction"))
    assertTrue(cycle.contains("if (invalidatesLegacyInteractionMaintenance)"))
    assertEquals(2, Regex("publishTicketInteractionStatus\\(").findAll(cycle).count())
    assertEquals(1, Regex("publishTicketRegistrationProof\\(").findAll(cycle).count())
    assertTrue(preparingGate >= 0)
    assertTrue(preparingPublication > preparingGate)
    assertTrue(terminalGate > preparingPublication)
    assertTrue(terminalProofPublication > terminalGate)
    assertTrue(terminalFailurePublication > terminalGate)
    assertTrue(classifier.contains("ticketCommandUsesLegacyRegistrationInteraction("))
  }

  @Test
  fun runtimeEventsUseOneBoundedSpacetimeSinkWithoutLogcat() {
    val record = body(service, "private fun recordTicketEvent", "private fun enqueueTicketSpacetimeTraceEvent")
    assertFalse(service.contains("android.util.Log"))
    assertFalse(service.contains("Log."))
    assertTrue(record.contains("event.take(96)"))
    assertTrue(record.contains("TicketTracePrivacy.allowlistedFields(detail)"))
    assertTrue(record.contains("enqueueTicketSpacetimeTraceEvent(cleanEvent, safeFields,"))
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
  fun startupTraceHasAuthoritativePixelCaptureAndForegroundPhases() {
    val capture = body(
      service,
      "private fun handleRootHardwareH264CaptureStateChanged",
      "private fun unexpectedHardwareEncoderRestart"
    )
    val foreground = body(
      service,
      "private suspend fun viviFocusedForFastPublicOpen",
      "private fun wakeRootDumpTimeoutMillis"
    )
    val keyFrame = body(
      service,
      "private fun handleRootHardwareH264CaptureFrame",
      "private suspend fun observeTicketDetailForWakeWithRoot"
    )
    assertTrue(capture.contains("health.active"))
    assertTrue(capture.contains("health.state == \"active\""))
    assertTrue(capture.contains("recordStartupTracePhase(\n        \"capture_helper_active\""))
    assertTrue(foreground.contains("focusedVivi"))
    assertTrue(foreground.contains("recordStartupTracePhase(\n        \"vivi_foreground_confirmed\""))
    assertTrue(keyFrame.contains("recordStartupTracePhase(\"first_keyframe_encoded\""))
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
    assertTrue(tracePublisher.contains("payload.string(\"traceId\")"))
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
    assertTrue(spacetimeWorker.contains("safeOperationalCorrelationId(correlationId)"))
  }

  @Test
  fun startupTraceCorrelationTravelsFromCommandToPixelTraceEvents() {
    val command = body(
      service,
      "internal suspend fun handleTicketSpacetimeCommand",
      "internal fun ticketSpacetimeSliderActive"
    )
    val trace = body(
      service,
      "private fun enqueueTicketSpacetimeTraceEvent",
      "private fun shouldPublishTicketTraceEvent"
    )
    assertFalse(command.contains("bindStartupTraceCorrelationIdFromCommand"))
    assertTrue(trace.contains("put(\"traceId\", traceId)"))
    assertTrue(spacetimeWorker.contains("noteStartupStartCommandReceived"))
    assertTrue(spacetimeWorker.contains("commandTraceId"))
    assertTrue(spacetimeWorker.contains("json.parseToJsonElement(command.payloadJson)"))
    assertTrue(spacetimeWorker.contains("startup_"))
  }

  @Test
  fun privateVideoSocketBindsOnlyBoundedStartupTraceBeforeOpenAndImmediateStart() {
    val socket = body(
      service,
      "private suspend fun acceptWebSocket",
      "private suspend fun startTicketSessionForVideoClientOpen"
    )
    val validator = body(
      service,
      "private fun boundedStartupTraceCorrelationId",
      "private fun JsonObject.stringValue"
    )
    val binding = body(
      service,
      "internal fun bindStartupTraceCorrelationIdFromVideoSocket",
      "internal fun noteStartupStartCommandReceived"
    )
    val currentSocketRecorder = body(
      service,
      "private fun recordTicketEventForCurrentVideoSocket",
      "private fun recordTicketEventForTrace"
    )
    val headerRead =
      "startupTraceCorrelationId = boundedStartupTraceCorrelationId(headers[\"x-ticket-startup-trace\"].orEmpty())"
    val duplicateClose = socket.indexOf("closeDuplicateViewerClients(info)")
    val bind = socket.indexOf(
      "bindStartupTraceCorrelationIdFromVideoSocket(\n          info.startupTraceCorrelationId,\n          info.generation"
    )
    val opened = socket.indexOf("recordTicketEventForCurrentVideoSocket(\n          \"stream_client_opened\"")
    val immediate = socket.indexOf("startTicketSessionForVideoClientOpen(info)")

    assertTrue(socket.contains(headerRead))
    assertTrue(validator.contains("clean.length != 16"))
    assertTrue(validator.contains("clean.startsWith(\"startup_\")"))
    assertTrue(validator.contains("it in '0'..'9' || it in 'a'..'f'"))
    assertTrue(binding.contains("val clean = boundedStartupTraceCorrelationId(value)"))
    assertTrue(binding.contains("startupTraceCorrelation.bindVideoSocket(clean, generation)"))
    assertTrue(binding.contains("startupTraceCorrelation.bindCommand(clean)"))
    assertTrue(currentSocketRecorder.contains("startupTraceCorrelation.isCurrentVideoSocket(traceId, info.generation)"))
    assertTrue(currentSocketRecorder.contains("return false"))
    listOf(duplicateClose, bind, opened, immediate).forEach { assertTrue(it >= 0) }
    assertTrue(bind < duplicateClose)
    assertTrue(bind < opened)
    assertTrue(bind < immediate)
  }

  @Test
  fun videoClientConfigIsFlushedBeforeAnyBinaryFrameCanBeScheduled() {
    val accept = body(
      service,
      "private suspend fun acceptWebSocket",
      "private suspend fun startTicketSessionForVideoClientOpen"
    )
    val broadcastConfig = body(
      service,
      "private fun broadcastConfig",
      "private fun sendConfigAndWarmStart"
    )
    val warmConfig = body(
      service,
      "private fun sendConfigAndWarmStart",
      "private fun markVideoClientConfigReady"
    )
    val markReady = body(
      service,
      "private fun newVideoClientDeliveryState",
      "private fun sendCachedKeyFrameOrRequest"
    )
    val sendFrame = body(
      service,
      "private fun sendVideoFrame",
      "private fun handleRootHardwareH264CaptureFrame"
    )
    val writer = body(
      service,
      "private fun launchVideoFrameWriter",
      "private fun applyVideoDeliveryEffects"
    )
    val delivery = source("ticket/TicketVideoClientDeliveryState.kt")
    val writerPump = source("ticket/TicketVideoClientWriterPump.kt")
    val configPump = source("ticket/TicketVideoClientConfigWriterPump.kt")
    val registry = source("ticket/TicketVideoClientDeliveryRegistry.kt")
    val webSocket = source("ticket/TicketWebSocket.kt")
    val socketWrite = body(
      webSocket,
      "private fun sendFrame(",
      "private fun BufferedInputStream.readRequired"
    )
    val stateCreated = accept.indexOf("videoSendStates.add(client, newVideoClientDeliveryState())")
    val clientPublished = accept.indexOf("videoClients.add(client)")

    assertTrue(accept.contains("binaryFramesInitiallyAllowed = !video"))
    assertTrue(stateCreated >= 0)
    assertTrue(stateCreated < clientPublished)
    assertTrue(broadcastConfig.contains("launchVideoClientConfigWriter(client, sendState, config"))
    assertTrue(warmConfig.contains("launchVideoClientConfigWriter(client, sendState, config"))
    assertTrue(markReady.contains("videoSendStates.replace(client, replacement)"))
    assertTrue(markReady.contains("return synchronized(encoderLock)"))
    assertTrue(markReady.indexOf("streamEpoch != expectedEpoch") < markReady.indexOf("videoSendStates.replace(client, replacement)"))
    assertTrue(markReady.contains("videoSendStates.ifCurrent(client, expectedState)"))
    assertTrue(registry.contains("previous.close()"))
    assertTrue(registry.contains("replacement.expectedEpoch < previous.expectedEpoch"))
    assertFalse(markReady.contains("getOrPut"))
    assertTrue(markReady.contains("expectedState.markConfigReady()"))
    assertTrue(markReady.contains("client.sendConfigAndAllowBinaryIf(config.message)"))
    assertTrue(markReady.contains("sendState.canAcceptConfig()"))
    assertTrue(markReady.contains("recordStartupTracePhase(\n          \"stream_config_sent\""))
    assertTrue(markReady.contains("if (videoDeliveryStateIsCurrent(client, sendState))"))
    assertTrue(markReady.contains("if (warmStart) \"video_client_warm_start\" else \"video_client_config_ready\""))
    assertFalse(markReady.contains("if (warmStart && videoDeliveryStateIsCurrent"))
    assertFalse(broadcastConfig.contains("recordStartupTracePhase(\"stream_config_sent\""))
    assertTrue(configPump.contains("durationMillis >= slowCloseMillis"))
    assertTrue(configPump.contains("onExpired(durationMillis)"))
    assertTrue(delivery.contains("if (!configReady)"))
    assertTrue(delivery.contains("frame.epoch != expectedEpoch"))
    assertTrue(sendFrame.contains("sendState.offer("))
    assertTrue(writer.contains("TicketVideoClientWriterPump("))
    assertTrue(writerPump.contains("state.canWrite(currentWriteToken)"))
    assertFalse(sendFrame.contains("videoSendStates.getOrPut"))
    assertTrue(webSocket.contains("requireBinaryAllowed && !binaryFramesAllowed"))
    assertTrue(webSocket.contains("|| !canSend()"))
    assertTrue(socketWrite.indexOf("output.flush()") < socketWrite.indexOf("binaryFramesAllowed = true"))
  }

  @Test
  fun videoFanoutUsesOneBoundedOrderedPumpPerClient() {
    val delivery = source("ticket/TicketVideoClientDeliveryState.kt")
    val writerPump = source("ticket/TicketVideoClientWriterPump.kt")
    val registry = source("ticket/TicketVideoClientDeliveryRegistry.kt")
    val sendFrame = body(service, "private fun sendVideoFrame", "private fun handleRootHardwareH264CaptureFrame")
    val writer = body(service, "private fun launchVideoFrameWriter", "private fun applyVideoDeliveryEffects")
    val effects = body(service, "private fun applyVideoDeliveryEffects", "private fun handleRootHardwareH264CaptureFrame")
    val cached = body(service, "private fun sendCachedKeyFrameOrRequest", "private fun broadcastFrame")

    assertTrue(delivery.contains("private val queued = ArrayDeque<QueuedFrame>()"))
    assertTrue(delivery.contains("queued.size < maxQueuedFrames"))
    assertTrue(delivery.contains("queuedBytes + frame.bytes.size <= maxQueuedBytes"))
    assertTrue(delivery.contains("nowMillis - oldest.queuedAtMillis > pendingMaxAgeMillis"))
    assertTrue(delivery.contains("frame.sequence != lastAdmittedSequence + 1L"))
    assertTrue(delivery.contains("lastAdmittedEpoch != frame.epoch"))
    assertTrue(delivery.contains("frame.sequence <= lastAdmittedSequence"))
    assertTrue(delivery.contains("requestKeyFrame = armKeyFrameRequest()"))
    assertTrue(delivery.contains("DROP_QUEUE_OVERFLOW"))
    assertTrue(sendFrame.contains("launchVideoFrameWriter(client, sendState, firstFrame, decision.writeToken)"))
    assertTrue(writer.contains("TicketVideoClientWriterPump("))
    assertTrue(writerPump.contains("while (nextFrame != null)"))
    assertTrue(writerPump.contains("state.completeWrite("))
    assertTrue(writerPump.contains("delay(slowCloseMillis)"))
    assertTrue(writerPump.contains("state.timeoutWrite("))
    assertTrue(writerPump.contains("writeFinished.compareAndSet(false, true)"))
    assertTrue(writerPump.contains("onWriteExpired("))
    assertTrue(writerPump.contains("currentWriteToken"))
    assertTrue(registry.contains("states[client] !== expectedState"))
    assertTrue(effects.contains("video_client_queue_drop"))
    assertTrue(effects.contains("video_client_sequence_gap"))
    assertTrue(cached.contains("cached.sequence == sourceTail.second"))
    assertTrue(cached.contains("cached.epoch == sourceTail.first"))
    assertTrue(service.contains("VIDEO_CLIENT_PENDING_MAX_FRAMES = 12"))
    assertTrue(service.contains("VIDEO_CLIENT_PENDING_MAX_BYTES = 5 * 1024 * 1024"))
  }

  @Test
  fun pendingStartupKeyFrameIsCoalescedAndConsumedOnceAfterEncoderStart() {
    val serviceRequest = body(
      service,
      "private fun requestKeyFrame",
      "private fun activeStreamStaleForRecovery"
    )
    val engineRequest = body(
      h264Engine,
      "fun requestKeyFrame",
      "fun requestControlCodeVisualProbe"
    )
    val engineFlush = body(
      h264Engine,
      "private fun flushPendingKeyFrameRequest",
      "suspend fun cleanupStaleProcesses"
    )
    val captureLoop = body(
      h264Engine,
      "private suspend fun runCaptureLoop",
      "private fun consumePendingCaptureRestartReason"
    )
    val engineConstruction = body(
      service,
      "private val pendingRootHardwareH264KeyFrame",
      "private var serverJob"
    )

    assertTrue(serviceRequest.contains("pendingRootHardwareH264KeyFrame.offer(reason)"))
    assertTrue(serviceRequest.contains("return"))
    assertTrue(engineConstruction.contains("pendingKeyFrameRequest = pendingRootHardwareH264KeyFrame"))
    assertTrue(engineRequest.contains("pendingKeyFrameRequest.offer(reason)"))
    assertTrue(engineRequest.contains("flushPendingKeyFrameRequest(\"request\")"))
    assertTrue(engineRequest.indexOf("pendingKeyFrameRequest.offer(reason)") <
      engineRequest.indexOf("flushPendingKeyFrameRequest(\"request\")"))
    assertTrue(engineFlush.contains("pendingKeyFrameRequest.take()"))
    assertEquals(1, engineFlush.split("writeHardwareKeyFrameRequest(").size - 1)
    assertTrue(engineFlush.contains("if (!writeHardwareKeyFrameRequest"))
    assertTrue(engineFlush.contains("pendingKeyFrameRequest.restoreIfEmpty(pending)"))
    assertTrue(captureLoop.indexOf("encoderProcess = localEncoder") < captureLoop.indexOf("flushPendingKeyFrameRequest(\"encoder_started\")"))
  }

  @Test
  fun sameCaptureConfigurationDoesNotRestartAnEncoderThatIsAlreadyStarting() {
    val start = body(
      h264Engine,
      "fun start(",
      "fun restart(reason: String)"
    )
    val activeJob = start.substringAfter("if (job?.isActive == true) {")
      .substringBefore("fps = targetFps\n      job = scope.launch")

    assertTrue(activeJob.contains("if (previousRequest != null && previousRequest != request)"))
    assertTrue(activeJob.contains("requestCaptureRestartLocked(\"capture_config_changed\")"))
    assertTrue(activeJob.contains("else {\n          publish()"))
    assertTrue(activeJob.contains("return"))
    assertEquals(1, activeJob.split("requestCaptureRestartLocked(").size - 1)
  }

  @Test
  fun firstAcceptedFrameUsesTheNewEpochAndIncreasingSequence() {
    val reset = body(service, "private fun resetFrameEpoch", "private fun ensureFrameEpoch")
    val receive = body(
      service,
      "private fun handleRootHardwareH264CaptureFrame",
      "private suspend fun observeTicketDetailForWakeWithRoot"
    )
    val envelope = body(service, "private fun broadcastFrame", "private fun sendVideoFrame")
    val initialDeltaGuard = receive.indexOf("if (!frame.keyFrame && latestKeyFrame == null)")
    val initialDeltaReturn = receive.indexOf("return", initialDeltaGuard)
    val acceptedBroadcast = receive.indexOf("broadcastFrame(")

    assertTrue(reset.contains("SystemClock.elapsedRealtime().coerceAtLeast(streamEpoch + 1)"))
    assertTrue(reset.contains("frameSequence = 0L"))
    assertTrue(reset.contains("latestKeyFrame = null"))
    assertTrue(
      reset.contains(
        "sendBitrateWindowBytes = 0L\n      invalidateVideoDeliveryStatesForEpoch(streamEpoch)\n    }"
      )
    )
    listOf(initialDeltaGuard, initialDeltaReturn, acceptedBroadcast).forEach { assertTrue(it >= 0) }
    assertTrue(initialDeltaGuard < initialDeltaReturn)
    assertTrue(initialDeltaReturn < acceptedBroadcast)
    assertTrue(envelope.contains("!streamActive ||"))
    assertTrue(envelope.contains("activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||"))
    assertTrue(receive.contains("ensureFrameEpoch(\"frame\")"))
    assertTrue(envelope.contains("!acceptedGeneration.matches("))
    assertTrue(envelope.contains("currentEpoch = streamEpoch"))
    assertTrue(envelope.contains("currentWidth = currentSize?.width"))
    assertTrue(envelope.contains("currentHeight = currentSize?.height"))
    assertTrue(envelope.contains("val epoch = streamEpoch"))
    assertTrue(envelope.contains("frameSequence += 1"))
    assertTrue(envelope.contains("buffer.putLong(epoch)"))
    assertTrue(envelope.contains("buffer.putLong(sequence)"))
    assertTrue(envelope.contains("latestKeyFrame = TicketCachedKeyFrame("))
    assertTrue(envelope.contains("epoch = epoch"))
    assertTrue(envelope.contains("sequence = sequence"))
    val frameMetric = envelope.indexOf("lastFrameSentAtMillis = sentAtMillis")
    val frameBarrierEnd = envelope.indexOf("    } ?: return")
    assertTrue(frameMetric >= 0)
    assertTrue(frameMetric < frameBarrierEnd)
    assertTrue(reset.contains("lastFrameSentAtMillis = 0L"))
    assertTrue(reset.contains("lastFrameBytes = 0"))
    assertTrue(receive.contains("val acceptedGeneration = synchronized(encoderLock)"))
    assertTrue(receive.contains("TicketVideoFrameGeneration("))
    assertTrue(receive.contains("lastKeyFrameEncodedAtMillis = encodedAtMillis"))
    val rejectedBroadcast = receive.indexOf(") ?: run {\n      hardwareCaptureSnapshot")
    val firstVisibleTrace = receive.indexOf("recordStartupTracePhase(\"first_visible_frame_sent\"")
    assertTrue(rejectedBroadcast >= 0)
    assertTrue(rejectedBroadcast < firstVisibleTrace)
    assertTrue(receive.contains("sequence=${'$'}{deliveredFrame.sequence}"))
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
      "\"stream_cadence\" ->",
      "\"force_ticket_reselect\" ->", "\"generate_control_code\" ->",
      "\"control_code_browser_capture\" ->", "\"control_exit\" ->"
    ).forEach { assertTrue("missing retained Spacetime command $it", commands.contains(it)) }
    assertTrue(commands.contains("spacetime_command_unsupported"))
  }

  @Test
  fun privateVideoSocketStartsCaptureImmediatelyAndKeepsSpacetimeAsDurableBackup() {
    val socket = body(
      service,
      "private suspend fun acceptWebSocket",
      "private suspend fun handleClientCommand"
    )
    val immediateStart = body(
      service,
      "private suspend fun startTicketSessionForVideoClientOpen",
      "private suspend fun handleClientCommand"
    )
    val commands = body(
      service,
      "internal suspend fun handleTicketSpacetimeCommand",
      "internal suspend fun handleTicketSpacetimeDesiredActive"
    )
    val durableStart = commands.substringAfter("\"start\" ->").substringBefore("\"activity\" ->")
    val serializedStart = body(
      service,
      "private suspend fun startTicketSession(",
      "private suspend fun startTicketSessionLocked"
    )

    assertTrue(socket.contains("if (video && !startTicketSessionForVideoClientOpen(info)) {\n      return"))
    assertFalse(socket.contains("stream_client_attached_without_session_start"))
    assertTrue(immediateStart.contains("lockedStartDecision = {"))
    assertTrue(immediateStart.contains("startupTraceCorrelation.resolveVideoSocketStart("))
    assertTrue(immediateStart.contains("decision = \"superseded\""))
    assertTrue(immediateStart.contains("streamActive ->"))
    assertTrue(immediateStart.contains("stream_client_immediate_start_coalesced"))
    assertTrue(immediateStart.contains("controlCodeRequestActive() ->"))
    assertTrue(immediateStart.contains("stream_client_start_deferred_for_control_code"))
    assertTrue(immediateStart.contains("stream_client_immediate_start"))
    assertTrue(immediateStart.contains("val response = startTicketSession("))
    assertTrue(immediateStart.contains("stream_client_immediate_start_result"))
    assertTrue(immediateStart.contains("recordTicketEventForCurrentVideoSocket("))
    assertTrue(immediateStart.contains("return decision != \"superseded\""))
    assertTrue(
      immediateStart.indexOf("startupTraceCorrelation.resolveVideoSocketStart(") <
        immediateStart.indexOf("streamActive ->")
    )
    assertTrue(serializedStart.contains("sessionMutex.withLock"))
    assertTrue(serializedStart.contains("lockedStartDecision?.invoke() ?: startTicketSessionLocked("))
    assertTrue(socket.indexOf("startTicketSessionForVideoClientOpen(info)") < socket.indexOf("ensureEncoderIfPossible()"))
    assertTrue(durableStart.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertTrue(durableStart.contains("startTicketSession().toTicketSpacetimeCommandResult(reason)"))
  }

  @Test
  fun relayCadenceDemandUsesOnlySupportedTiersAndKeepsTheCodecAlive() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val cadence = commands.substringAfter("\"stream_cadence\" ->").substringBefore("\"recover_stream\" ->")
    assertTrue(cadence.contains("payload?.stringValue(\"demand\")"))
    assertTrue(cadence.contains("payload?.longValue(\"maxFps\")"))
    assertTrue(cadence.contains("TicketCaptureCadenceScheduler.isSupportedFps(targetFps)"))
    assertTrue(cadence.contains("rootHardwareH264CaptureEngine.requestCadence("))
    assertTrue(cadence.contains("keyframe_only"))
    assertFalse(cadence.contains("rootHardwareH264CaptureEngine.restart"))
    assertFalse(cadence.contains("rootHardwareH264CaptureEngine.stop"))
  }

  @Test
  fun durableKeyframeAlwaysReachesTheCoalescedEncoderRequestPath() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val keyframe = commands.substringAfter("\"keyframe\" ->").substringBefore("\"stream_cadence\" ->")

    assertTrue(keyframe.contains("requestKeyFrame(reason.ifBlank { \"spacetime_keyframe\" })"))
    assertTrue(keyframe.contains("reason = \"keyframe_requested\""))
    assertFalse(keyframe.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertFalse(keyframe.contains("stream_already_healthy"))
    assertTrue(h264Engine.contains("pendingKeyFrameRequest.offer(reason)"))
    assertTrue(h264Main.contains("requestImmediateSyncFrame(syncFrameRequested, cadenceScheduler, frameWaitLock)"))
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
  fun everyColdStartSharesReadinessAndRetriesOnlyAfterAFailedProbe() {
    val start = body(
      service,
      "private suspend fun startTicketSessionLocked",
      "private fun tryReuseActiveHardwareStreamBeforePreflight"
    )
    val readiness = body(
      service,
      "private suspend fun awaitRootHardwareH264StartupReadiness(",
      "private suspend fun awaitRootHardwareH264StartupReadinessForControlCodeRequest"
    )

    assertTrue(start.contains("awaitRootHardwareH264StartupReadinessForControlCodeRequest()"))
    assertTrue(start.contains("awaitRootHardwareH264StartupReadiness(\"session_start\")"))
    assertFalse(start.contains("rootHardwareH264CaptureEngine.probe()"))
    assertTrue(readiness.contains("waitJob?.join()"))
    assertTrue(readiness.contains("if (!health.available)"))
    assertTrue(readiness.contains("startRootHardwareH264StartupReadiness()"))
    assertTrue(readiness.contains("retryJob?.join()"))
    assertTrue(readiness.contains("retryJob !== waitJob"))
    assertTrue(readiness.contains("root_hardware_h264_startup_readiness_wait"))
  }

  @Test
  fun preparingSocketStartCoalescesBeforeTheSharedStartupTraceCanBeReset() {
    val start = body(
      service,
      "private suspend fun startTicketSessionLocked",
      "private fun tryReuseActiveHardwareStreamBeforePreflight"
    )
    val coalesce = start.indexOf("shouldCoalescePreparingTicketStreamBeforePreflight")
    val traceReset = start.indexOf("beginStartupTrace(\"session_start\")")

    assertTrue(coalesce >= 0)
    assertTrue(traceReset > coalesce)
    assertTrue(start.substring(coalesce, traceReset).contains("return TicketSessionResponse"))
  }

  @Test
  fun coldCaptureAdmissionIsAtomicWithAllControlOwnershipStates() {
    val start = body(
      service,
      "private suspend fun startTicketSessionLocked",
      "private fun tryReuseActiveHardwareStreamBeforePreflight"
    )
    val controlOwnership = body(
      service,
      "internal fun ticketSpacetimeControlCodeRequestActive",
      "private fun markControlCodeModeEntered"
    )

    assertTrue(start.contains("streamStartAdmission.admit("))
    assertTrue(start.contains("controlCodeOwnsStart = controlCodeOwnsStart"))
    assertTrue(start.contains("additionalControlOwnershipActive = ::ticketSpacetimeControlCodeRequestActive"))
    assertTrue(start.indexOf("streamStartAdmission.admit(") < start.indexOf("scheduleRootHardwareH264CaptureStart("))
    assertTrue(controlOwnership.contains("ticketControlOwnershipActive("))
    assertTrue(controlOwnership.contains("pendingControlCodeBrowserCaptureRequestId != null"))
    assertTrue(controlOwnership.contains("controlModeActive = controlCodeModeActive"))
    assertTrue(controlOwnership.contains("sessionState = ticketSessionState"))
  }

  @Test
  fun interactionBoundariesUseTwoFreshRootedVisualProbesWithoutHierarchy() {
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
    assertTrue(preparation.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(popup.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(popup.contains("awaitStableControlCodeSubmitLayout"))
    assertFalse(preparation.contains("TicketViviPageEnforcer"))
    assertFalse(popup.contains("TicketViviPageEnforcer"))
    assertTrue(service.contains("CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT = 4"))
    assertTrue(service.contains("requestTicketDetailVisualProbe"))
    assertFalse(service.contains("control_code_fast_interaction_hierarchy_observed"))
    assertTrue(popup.contains("layoutProbe?.result"))
    assertTrue(popup.contains("visualControlCodePopupTransaction(layoutProbe)"))
    assertFalse(popup.contains("point(48"))
    assertFalse(service.contains("point(48, 66"))
    assertFalse(service.contains("point(48, 78"))
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
    val claim = body(service, "private suspend fun claimControlCodeAutomationForRequest", "private suspend fun releaseControlCodeAutomationForRequest")
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
  fun coldTicketPreparationUsesOnlyFreshV3CurrentVisualState() {
    val currentVisual = body(
      service,
      "private suspend fun observeTicketDetailForFastPublicOpenCurrentVisualProof",
      "private suspend fun verifyFreshTicketDetailVisualProof"
    )
    assertTrue(currentVisual.contains("currentOnly = true"))
    assertTrue(currentVisual.contains("allowUnknown = false"))
    assertTrue(currentVisual.contains("remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)"))
    assertTrue(currentVisual.contains("timeoutMillis = visualProofBudgetMillis"))
    assertFalse(currentVisual.contains("timeoutMillis = TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.ACTIVATED_DETAIL"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.UNACTIVATED_DETAIL"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.TICKET_LIST"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.LOGIN_REQUIRED"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.BLOCKED"))
    assertTrue(currentVisual.contains("TicketVisualPhoneState.UNKNOWN"))
    assertTrue(currentVisual.contains("TicketFastOpenVisualReadinessPolicy.decide(observation.state)"))
    assertTrue(fastOpenReadinessPolicy.contains("TicketVisualPhoneState.VIVI_HOME"))
    assertTrue(fastOpenReadinessPolicy.contains("success = true"))
    assertTrue(fastOpenReadinessPolicy.contains("TicketVisualPhoneState.UNKNOWN ->"))
    assertTrue(fastOpenReadinessPolicy.contains("success = false"))
    assertFalse(currentVisual.contains("backBounds"))
    assertFalse(currentVisual.contains("verifyFreshTicketDetailVisualProof"))
    assertFalse(currentVisual.contains("observeRootViviStateForWake"))
    assertFalse(currentVisual.contains("runFastNonTouchInput"))

    val preparation = body(
      service,
      "private suspend fun prepareViviForRootHardwareH264FastOpen",
      "private fun allowProvisionalHardwareH264FramesForFocusedVivi"
    )
    assertTrue(preparation.contains("wakeStartedAtMillis = wakeStartedAtMillis"))
    assertTrue(service.contains("TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L"))
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
  fun stalePreparationFailsClosedOnUnprovedVisualState() {
    val preparation = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    assertTrue(preparation.contains("visual_state_unknown"))
    assertTrue(preparation.contains("missing_visual_action"))
    assertTrue(service.contains("CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS = 8_000L"))
    assertFalse(preparation.contains("display_geometry"))
    assertFalse(preparation.contains("fallback"))
  }

  @Test
  fun fastStateReprovesFreshRootedDetailVisuallyWithoutHierarchyPrefetch() {
    val preflight = body(service, "private suspend fun ensureControlCodeRequestPreflight", "private fun markTicketNonTouchAction")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(preflight.contains("awaitStableTicketVisualActionObservation"))
    assertFalse(preflight.contains("recentTicketDetailHierarchyWithin"))
    assertFalse(preflight.contains("controlCodeRequestRootHierarchy"))
    assertFalse(generate.contains("ticketDetailHierarchyDeferred"))
    assertFalse(generate.contains("control_code_root_prefetch"))
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
  fun staleRevisionUsesFreshRootedVisualSamplesWithoutRootDump() {
    val preflight = body(service, "private suspend fun ensureControlCodeRequestPreflight", "private fun markTicketNonTouchAction")
    val reuse = body(service, "private suspend fun reuseRecentTicketDetailForControlCode", "private fun markTicketNonTouchAction")
    assertTrue(preflight.contains("reuseRecentTicketDetailForControlCode(phases, requestStartedAtMillis)"))
    assertFalse(reuse.contains("recentTicketDetailHierarchyWithin"))
    assertFalse(reuse.contains("TicketViviPageEnforcer"))
    assertTrue(reuse.contains("ticketSessionState != TICKET_SESSION_CONTROL_EXIT"))
    assertTrue(reuse.contains("awaitStableTicketVisualActionObservation("))
    assertTrue(reuse.contains("updateTicketSessionState(TICKET_SESSION_LIVE, \"control_code_recent_detail_reused\")"))
    assertTrue(reuse.contains("CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS"))
    assertTrue(reuse.contains("source=root_h264_recent_detail_visual_proof fast_revision_missed=true"))
  }

  @Test
  fun requestLaneStartsKeyboardClampAsynchronouslyAndReleasesItInFinally() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(generate.contains("RequestScopedKeyboardClampLease"))
    assertTrue(generate.contains("lease.startAsync()"))
    assertTrue(service.contains("awaitControlCodeKeyboardClamp"))
    assertTrue(service.contains("CoroutineStart.DEFAULT"))
    assertTrue(service.contains("keyboard_clamp_requested"))
    assertTrue(service.contains("keyboard_clamp_applied"))
    assertTrue(service.contains("keyboard_clamp_released"))
    assertTrue(generate.contains("withContext(NonCancellable)"))
    assertTrue(generate.indexOf("lease.startAsync()") < generate.indexOf("ensureControlCodeRequestPreflight("))
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
  fun streamRunsOneFpsIdleAndTenFpsDuringControlDispatch() {
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_STEADY_FPS = 1"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_CONTROL_CODE_REQUEST_FPS = ROOT_HARDWARE_H264_ACTIVE_FPS"))
    assertTrue(h264Engine.contains("controlCodeRequestFpsTarget = TicketScreenConfig.ROOT_HARDWARE_H264_CONTROL_CODE_REQUEST_FPS"))
    assertTrue(service.contains("targetFps = TicketScreenConfig.ROOT_HARDWARE_H264_STEADY_FPS"))
  }

  @Test
  fun tenFpsBurstBeginsAtBrowserDispatchBeforePhoneOwnershipWait() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val burst = "startControlCodeRequestBurst(\"control_code_browser_dispatch\")"
    assertTrue(generate.contains(burst))
    assertTrue(generate.indexOf(burst) < generate.indexOf("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(generate.contains("capture_burst_started"))
  }

  @Test
  fun tenFpsBurstStopsOnBrowserAckTimeoutAndFinally() {
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
    assertTrue(generate.indexOf("waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)") < generate.indexOf("returnControlCodeSurfaceToRawTicket("))
    assertFalse(generate.contains("startControlCodeCleanupCloseHierarchyPrefetch"))
    assertTrue(clean.contains("requestControlCodeCleanupVisualProbe"))
    assertTrue(clean.contains("TicketControlCodeCleanupVisualProof"))
    assertFalse(clean.contains("finalHierarchyPrefetch"))
    assertFalse(clean.contains("TicketViviPageEnforcer"))
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
    val finalVisualProbe = "awaitStableTicketVisualActionObservation("
    val watermarkCall = "requestFreshControlCodeFrameWatermark(reason)"
    assertTrue(service.contains("CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS = 200L"))
    assertTrue(marker.contains(delayEvent))
    assertTrue(marker.contains("measureInputPhase(phases, \"result_marker_delay\")"))
    assertTrue(marker.indexOf(delayEvent) < marker.indexOf(finalVisualProbe))
    assertTrue(marker.indexOf(finalVisualProbe) < marker.indexOf(watermarkCall))
    assertTrue(marker.contains("ticketControlCodeReturnedToSameDetail("))
    assertTrue(marker.contains("TicketControlCodeVisualResultMode.LEGACY_GENERATED_WITH_CLOSE"))
    assertFalse(marker.contains("expectedVisualSignature"))
  }

  @Test
  fun restartedRootEncoderDropsFramesFromThePreviousProcess() {
    val stop = body(h264Engine, "private fun stopProcesses()", "private fun consumeCleanStopForFastStart")
    assertTrue(h264Engine.contains("private val captureGeneration = AtomicLong(0L)"))
    assertTrue(h264Engine.contains("val parserGeneration = advanceCaptureGeneration()"))
    assertTrue(h264Engine.contains("if (parserGeneration == captureGeneration.get())"))
    assertTrue(stop.contains("advanceCaptureGeneration()"))
  }

  @Test
  fun rootEncoderKeepsHardwareCaptureAliveUntilInputIsDrained() {
    val loop = body(h264Main, "while (frames <= 0 || sent < frames)", "encoder.signalEndOfInputStream()")
    val draw = loop.indexOf("drawBitmap(inputSurface")
    val drain = loop.indexOf("TicketEncoderDrainProgress drainProgress")
    val close = loop.indexOf("source.close()")
    assertTrue(draw >= 0)
    assertTrue(drain > draw)
    assertTrue(close > drain)
    assertTrue(loop.contains("hardware-backed capture result alive until this frame's encoder drain"))
  }

  @Test
  fun rootEncoderUsesTruthfulHighBrightnessSdrColorProfile() {
    val paint = body(h264Main, "private static Paint hardwareColorCorrectionPaint()", "private static void drawBitmapOnCanvas")
    assertTrue(h264Main.contains("MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709"))
    assertTrue(h264Main.contains("MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED"))
    assertTrue(h264Main.contains("MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO"))
    assertTrue(h264Main.contains("HIGH_BRIGHTNESS_SDR_RED_GAIN = 1.08f"))
    assertTrue(h264Main.contains("HIGH_BRIGHTNESS_SDR_GREEN_GAIN = 1.05f"))
    assertTrue(h264Main.contains("HIGH_BRIGHTNESS_SDR_BLUE_GAIN = 1.03f"))
    assertTrue(paint.contains("0f, 0f, HIGH_BRIGHTNESS_SDR_RED_GAIN, 0f, 0f"))
    assertTrue(paint.contains("0f, HIGH_BRIGHTNESS_SDR_GREEN_GAIN, 0f, 0f, 0f"))
    assertTrue(paint.contains("HIGH_BRIGHTNESS_SDR_BLUE_GAIN, 0f, 0f, 0f, 0f"))
    assertTrue(paint.contains("Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG"))
    assertTrue(paint.contains("paint.setFilterBitmap(true)"))
    assertTrue(paint.contains("paint.setDither(true)"))
    assertFalse(paint.contains("0f, 0f, 1f, 0f, 0f"))

    val screenConfig = source("ticket/TicketScreenConfig.kt")
    assertTrue(screenConfig.contains("red_blue_swap_high_brightness_sdr_gpu_paint_r1.08_g1.05_b1.03"))
    assertTrue(screenConfig.contains("ROOT_HARDWARE_H264_COLOR_STANDARD = \"bt709_limited_sdr\""))
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
  fun controlCodeOpenUsesFreshRootVisualGeometryBeforeTyping() {
    val type = body(service, "private suspend fun executeRootControlCodeType", "private suspend fun tapControlCodePointWithoutKeyboard")
    val open = body(service, "private suspend fun openControlCodePopupFastForRequest", "private fun markOpenedControlCodePopupTransactionReady")
    assertTrue(open.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(open.contains("awaitStableControlCodeSubmitLayout"))
    assertTrue(open.contains("rooted_visual_probe"))
    assertFalse(open.contains("TicketViviPageEnforcer"))
    assertFalse(open.contains("fallbackControlCodeButtonTarget"))
    assertTrue(open.contains("postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS"))
    assertTrue(type.contains("TicketControlCodeRootInput.buildTypeScript("))
    assertFalse(type.contains("openX = transaction.open?.x"))
    assertFalse(type.contains("openY = transaction.open?.y"))
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
    assertTrue(wait.contains("preSubmitDetailAnchor"))
    assertTrue(wait.contains("preSubmitDetailState"))
    assertTrue(wait.contains("awaitStableTicketVisualActionObservation("))
    assertTrue(wait.contains("ticketControlCodeReturnedToSameDetail("))
    assertTrue(wait.contains("REDESIGNED_DETAIL_ALREADY_CLEAN"))
    assertTrue(wait.contains("LEGACY_GENERATED_WITH_CLOSE"))
    assertTrue(wait.contains("ticketControlCodeRedesignedDetailCandidate(visualProbe)"))
    assertTrue(wait.contains("previousLegacyGeneratedProbe?.closeBounds == visualProbe.closeBounds"))
    assertFalse(wait.contains("TicketControlCodeVisualSignatureProof"))
    assertFalse(wait.contains("changedVisualProof"))
    assertTrue(wait.contains("resultProof = \"phone_visual_root_confirmed\""))
    assertFalse(wait.contains("resultProof = \"phone_visual_signature\""))
    assertTrue(wait.contains("CONTROL_CODE_MARKER_RESULT_HIERARCHY"))
    assertTrue(wait.contains("visual_only=true"))
    assertFalse(wait.contains("controlExitHierarchy"))
    assertFalse(wait.contains("TicketViviPageEnforcer"))
    assertFalse(wait.contains("raw_ticket_after_submit_generated"))
  }

  @Test
  fun cleanupUsesTheFreshDetectedBadgeCrossAndAOneShotRootInputPath() {
    val begin = body(service, "private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun finishGeneratedControlCodeResultFastCleanup")
    val send = body(service, "private suspend fun sendFastGeneratedResultCloseTap", "private suspend fun waitForCleanTicketSurfaceFast")
    val oneShot = body(service, "private suspend fun runFastOneShotControlSurfaceCloseInput", "private suspend fun runFastRecoveryInput")
    assertTrue(begin.contains("awaitStableLegacyGeneratedControlCodeCloseProbe()"))
    assertTrue(begin.contains("ticketControlCodeReturnedToSameDetail("))
    assertTrue(begin.contains("control_code_same_detail_noop"))
    assertTrue(begin.indexOf("ticketControlCodeReturnedToSameDetail(") < begin.indexOf("awaitStableLegacyGeneratedControlCodeCloseProbe()"))
    assertTrue(begin.contains("TicketControlCodeVisualResultMode.REDESIGNED_DETAIL_ALREADY_CLEAN"))
    assertTrue(begin.contains("TicketControlCodeVisualResultMode.LEGACY_GENERATED_WITH_CLOSE"))
    assertFalse(begin.contains("ticketControlCodeExpectedDetailSignatureMatches("))
    assertFalse(begin.contains("KEYCODE_BACK"))
    assertFalse(begin.contains("closeProbe?.result == TicketControlCodeVisualClassifier.RAW_TICKET"))
    assertTrue(begin.contains("closeProbe?.closeBounds"))
    assertTrue(begin.contains("controlCodeVisualBoundsToDevice(closeBounds)"))
    assertTrue(begin.contains("control_code_generated_close_visual"))
    assertFalse(begin.contains("TicketViviPageEnforcer"))
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
  fun redesignedControlResultUsesExplicitSameDetailModeAndNeverSignatureBack() {
    val stable = body(
      service,
      "private suspend fun awaitStableLegacyGeneratedControlCodeCloseProbe",
      "private fun controlCodeVisualBoundsToDevice"
    )
    val clean = body(
      service,
      "private suspend fun waitForCleanTicketSurfaceFast",
      "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup"
    )
    val finish = body(
      service,
      "private suspend fun finishGeneratedControlCodeResultFastCleanup",
      "private suspend fun sendFastGeneratedResultCloseTap"
    )
    val begin = body(
      service,
      "private suspend fun beginGeneratedControlCodeResultFastClose",
      "private suspend fun finishGeneratedControlCodeResultFastCleanup"
    )
    assertTrue(stable.contains("legacyGeneratedAgrees"))
    assertTrue(stable.contains("previous?.closeBounds == current.closeBounds"))
    assertFalse(stable.contains("visualSignature =="))
    assertTrue(clean.contains("val baselineSignatureMatches"))
    assertTrue(clean.contains("controlCodeSignatureCleanupRequired && baselineSignatureMatches"))
    assertFalse(clean.contains("visualProbe.result == TicketControlCodeVisualClassifier.RAW_TICKET &&\n        !signatureStillGenerated"))
    assertTrue(finish.contains("control_code_same_detail_noop"))
    assertTrue(finish.contains("same_detail_h264_verified"))
    assertTrue(begin.contains("activeControlCodeVisualResultMode"))
    assertFalse(begin.contains("control_code_generated_signature_back"))
    assertFalse(begin.contains("KEYCODE_BACK"))
    assertFalse(finish.substringBefore("recordTicketEvent(\n      \"control_code_fast_cleanup_phase\"").contains("input keyevent"))
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
      "private suspend fun tryDismissOpenControlCodePopupAfterInputFailure"
    )
    assertTrue(cleanup.contains("tryDismissOpenControlCodePopupAfterInputFailure("))
    assertTrue(cleanup.contains("waitForCleanTicketSurfaceFast("))
    assertTrue(cleanup.contains("beginGeneratedControlCodeResultFastClose("))
    assertFalse(cleanup.contains("controlExitHierarchy"))
    assertFalse(cleanup.contains("TicketViviPageEnforcer"))
  }

  @Test
  fun failedEntryPopupUsesTheSubmitProofAndBackBeforeBroadRecovery() {
    val cleanup = body(
      service,
      "private suspend fun returnControlCodeSurfaceToRawTicket",
      "private suspend fun tryDismissOpenControlCodePopupAfterInputFailure"
    )
    assertTrue(cleanup.contains("tryDismissOpenControlCodePopupAfterInputFailure("))
    assertTrue(cleanup.contains("generatedHierarchy.isBlank()"))
    val popup = body(
      service,
      "private suspend fun tryDismissOpenControlCodePopupAfterInputFailure",
      "private suspend fun beginGeneratedControlCodeResultFastClose"
    )
    assertTrue(popup.contains("awaitStableControlCodeSubmitLayout(requireGeometry = false)"))
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
    assertTrue(tracePublisher.contains("client.safeLogRetainedAsync("))
    assertTrue(tracePublisher.contains("service.enqueueTicketSpacetimePhoneMessage(originalPhoneMessage)"))
    assertFalse(tracePublisher.contains("client.safeLogRetained("))
    assertTrue(tracePublisher.contains("retainedTicketTraceOperationalLogId(payload, event)"))
    assertTrue(queue.contains("fun enqueueRetained("))
    assertTrue(queue.contains("restoreUndelivered()"))
    assertTrue(queue.contains("sender(event)"))
    assertTrue(queue.contains("catch (_: Throwable)"))
  }

  @Test
  fun requiredStartupTracePhasesRemainRetainedUntilConfirmedOperationalWrite() {
    val tracePublisher = body(
      spacetimeWorker,
      "private suspend fun publishTicketTraceEvent",
      "private fun JsonObject.string"
    )
    assertTrue(service.contains("criticalReplacement = ::ticketSpacetimeCriticalMessageReplacement"))
    assertTrue(tracePublisher.contains("TicketSpacetimeCriticalMessagePolicy.replacement(payload)"))
    assertTrue(tracePublisher.contains("stableId = retainedTicketTraceOperationalLogId(payload, event)"))
    assertTrue(tracePublisher.contains("correlationId = startupReplacement.generation"))
    assertTrue(tracePublisher.contains("replacementSocketGeneration = startupReplacement.socketGeneration ?: 0L"))
    assertTrue(tracePublisher.contains("return client.safeLogRetainedAsync("))
    assertTrue(spacetimeWorker.contains("fun enqueueRetained("))
    assertTrue(spacetimeWorker.contains("retainedWake.trySend(Unit)"))
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
    assertTrue(begin.contains("awaitStableLegacyGeneratedControlCodeCloseProbe()"))
    assertTrue(begin.contains("closeProbe?.closeBounds"))
    assertTrue(begin.contains("controlCodeVisualBoundsToDevice(closeBounds)"))
    assertFalse(begin.contains("controlExitHierarchy"))
    assertFalse(begin.contains("TicketViviPageEnforcer"))
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
    assertFalse(service.contains("CONTROL_CODE_FAST_CLEANUP_ROOT_DUMP_TIMEOUT_MILLIS"))
  }

  @Test
  fun successfulBrowserCaptureTargetsAztecTicketDetailAndNeverTheRegistrationList() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val returnRaw = body(service, "private suspend fun returnControlCodeSurfaceToRawTicket", "private suspend fun tryDismissOpenControlCodePopupAfterInputFailure")
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
  fun activeGuardKeepsAVisuallyProvedTicketListLiveWithoutLegacyNavigation() {
    val guard = body(service, "private suspend fun enforceViviTicketPageIfNeeded", "private suspend fun attemptActiveGuardRecoveryAction")
    assertTrue(guard.contains("active_guard_ticket_list_ready"))
    assertTrue(guard.contains("updateTicketSessionState(TICKET_SESSION_LIVE, \"active_guard_ticket_list_\$reason\")"))
    assertFalse(guard.contains("isTicketListWithCardAndRegistrationButton"))
    assertFalse(guard.contains("ticketCardDetailActionForHierarchy"))
  }

  @Test
  fun cleanupRequiresRepeatedRawTicketVisualProof() {
    val clean = body(service, "private suspend fun waitForCleanTicketSurfaceFast", "private suspend fun completeFastVerifiedTicketDetailControlExitCleanup")
    assertTrue(clean.contains("TicketControlCodeCleanupVisualProof(CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT)"))
    assertTrue(clean.contains("TicketControlCodeVisualClassifier.RAW_TICKET"))
    assertTrue(clean.contains("rawTicketConfirmed"))
    assertTrue(clean.contains("raw_ticket_fast_proof"))
    assertTrue(clean.contains("ticketControlCodeExpectedDetailSignatureMatches("))
    assertTrue(clean.contains("activeControlCodeRawVisualSignature"))
    assertTrue(clean.contains("activeControlCodeVisualSignatureEpoch"))
    assertTrue(clean.contains("controlCodeSignatureCleanupRequired &&"))
    assertTrue(clean.contains("TicketControlCodeVisualClassifier.UNKNOWN"))
  }

  @Test
  fun visualSignatureAnchorsStayPrivateAndCleanupPendingSurvivesAProcessRestart() {
    val create = body(service, "override fun onCreate()", "override fun onStartCommand")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val requestFinally = generate.substringAfter("} finally {").substringBefore("releaseControlCodeAutomationForRequest")
    val complete = body(service, "private suspend fun completeControlExitCleanup", "private suspend fun waitForFreshStreamFrameAfterCleanup")
    assertTrue(create.contains("CONTROL_CODE_VISUAL_CHECKPOINT_PENDING_KEY"))
    assertTrue(service.contains("persistControlCodeSignatureCleanupRequired(true)"))
    assertTrue(service.contains("if (!editor.commit()) return false"))
    assertTrue(complete.contains("persistControlCodeSignatureCleanupRequired(false)"))
    assertTrue(generate.contains("scheduleControlCodeVisualSignatureExpiry()"))
    assertFalse(requestFinally.contains("activeControlCodeRawVisualSignature = \"\""))
    assertFalse(requestFinally.contains("activeControlCodeGeneratedVisualSignature = \"\""))
    assertFalse(requestFinally.contains("activeControlCodeRawDetailAnchor = \"\""))
    assertFalse(service.contains("recordTicketEvent(\"visual_signature"))
    assertFalse(service.contains("sendTicketStateEvent(visualSignature"))
  }

  @Test
  fun controlCodeBaselineIsCanonicalizedByAVisualListReopen() {
    val open = body(service, "private suspend fun openControlCodePopupFastForRequest", "private suspend fun canonicalizeControlCodeTicketDetail")
    val canonicalize = body(service, "private suspend fun canonicalizeControlCodeTicketDetail", "private suspend fun awaitStableControlCodeRawVisualSignature")
    assertTrue(open.contains("canonicalizeControlCodeTicketDetail("))
    assertTrue(canonicalize.contains("initial.backBounds"))
    assertTrue(canonicalize.contains("TicketVisualPhoneState.TICKET_LIST"))
    assertTrue(canonicalize.contains("ticketVisualSwitchAnchors"))
    assertTrue(canonicalize.contains("TicketVisualPhoneState.ACTIVATED_DETAIL"))
    assertTrue(canonicalize.contains("activatedCardForControlCode("))
    assertTrue(canonicalize.contains("ticketVisualControlCodeActivatedDetailProved("))
    assertTrue(canonicalize.contains("restoreControlCodeUnactivatedDetailFromList("))
    assertTrue(canonicalize.contains("control_code_recent_activated_unavailable"))
    val restore = body(
      service,
      "private suspend fun restoreControlCodeUnactivatedDetailFromList",
      "private suspend fun awaitStableControlCodeRawVisualSignature"
    )
    assertTrue(restore.contains("list.latestRegistrationCard()?.registrationBounds"))
    assertTrue(restore.contains("ticketVisualControlCodeUnactivatedDetailRestored(initial, restored)"))
    assertFalse(restore.contains("activatedDetailBounds"))
    assertFalse(restore.contains("repeat("))
    assertFalse(canonicalize.contains("latestCard()"))
    assertFalse(canonicalize.contains("switchAvailable("))
    assertFalse(canonicalize.contains("UiAutomator"))
    assertFalse(canonicalize.contains("dumpViviHierarchy"))
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
    assertTrue(exit.contains("ticketControlCodeCleanupRequiresVisualReopen("))
    assertTrue(exit.contains("controlCodeSignatureCleanupRequired"))
    assertTrue(exit.contains("activeControlCodeVisualResultMode"))
    assertTrue(exit.contains("activeControlCodeRawDetailAnchor"))
    assertTrue(exit.contains("control_exit_cleanup_requires_visual_reopen"))
    assertTrue(exit.contains("volatile_visual_identity_unavailable"))
    assertTrue(exit.indexOf("ticketControlCodeCleanupRequiresVisualReopen(") < exit.indexOf("returnControlCodeSurfaceToRawTicket"))
    val strictCleanupIndex = exit.indexOf("returnControlCodeSurfaceToRawTicket")
    val strictFailureGateIndex = exit.indexOf("if (controlCodeSignatureCleanupRequired)", strictCleanupIndex)
    val genericPreparationIndex = exit.indexOf("prepareTicketDetailForControlCodeRequest")
    assertTrue(strictFailureGateIndex > strictCleanupIndex)
    assertTrue(strictFailureGateIndex < genericPreparationIndex)
    assertTrue(exit.contains("control_exit_strict_visual_cleanup_unproved"))
    assertTrue(exit.contains("strict_visual_identity_unproved"))
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
    assertTrue(start.contains("awaitRootHardwareH264StartupReadiness"))
    assertTrue(service.contains("rootHardwareH264CaptureEngine.probe()"))
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
    assertTrue(service.contains("TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 2_000L"))
    assertFalse(service.contains("fastWakeReadyFromRecentTicketDetail(reason, wakeStartedAtMillis)"))
  }

  @Test
  fun coldOpenUsesOnlyCurrentV3VisualProof() {
    val prepare = body(
      service,
      "private suspend fun prepareViviForRootHardwareH264FastOpen",
      "private fun scheduleRootHardwareH264CaptureStart"
    )
    assertTrue(prepare.contains("observeTicketDetailForFastPublicOpenCurrentVisualProof"))
    assertFalse(prepare.contains("observeTicketDetailForFastPublicOpenRootProof"))
    assertFalse(prepare.contains("observeTicketDetailForFastPublicOpenVisibleProof"))
    assertFalse(prepare.contains("verifyFreshTicketDetailVisualProof"))
    assertTrue(prepare.contains("allowKnownViviState = true"))
    assertTrue(prepare.contains("Details and lists remain viewable without hierarchy"))
    val capturePreparation = body(
      service,
      "private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership",
      "private suspend fun verifyRootHardwareSecureCapturePixelsVisible"
    )
    assertTrue(capturePreparation.contains(
      "TicketViviRecoveryState.TICKET_LIST_WITH_CARD -> TICKET_PIXEL_STATE_TICKET_LIST"
    ))
    assertTrue(capturePreparation.contains("else -> null"))
    assertTrue(capturePreparation.contains("session_start_known_non_ticket_view_ready"))
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
  fun publicOpenPublishesTicketListWithoutClaimingRawTicketReadiness() {
    val prepare = body(
      service,
      "private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership",
      "private suspend fun verifyRootHardwareSecureCapturePixelsVisible"
    )
    assertTrue(prepare.contains("TicketViviRecoveryState.TICKET_DETAIL -> TICKET_PIXEL_STATE_RAW_TICKET"))
    assertTrue(prepare.contains("TicketViviRecoveryState.TICKET_LIST_WITH_CARD -> TICKET_PIXEL_STATE_TICKET_LIST"))
    assertTrue(prepare.contains("else -> null"))
    assertTrue(prepare.contains("session_start_ticket_list_ready"))
    assertTrue(service.contains("private const val TICKET_PIXEL_STATE_TICKET_LIST = \"ticket_list\""))
  }

  @Test
  fun activeH264StreamCanBeReusedBeforeHeavyPreflight() {
    val start = body(service, "private suspend fun startTicketSessionLocked", "private fun tryReuseActiveHardwareStreamBeforePreflight")
    val reuse = body(service, "private fun tryReuseActiveHardwareStreamBeforePreflight", "private fun reuseActiveHardwareStream")
    assertTrue(start.contains("tryReuseActiveHardwareStreamBeforePreflight()?.let { return it }"))
    assertTrue(start.indexOf("tryReuseActiveHardwareStreamBeforePreflight") < start.indexOf("PhonePortraitLock.ensureVerified"))
    assertTrue(reuse.contains("canReuseActiveHardwareStreamWithoutRootRevalidation"))
  }

  @Test
  fun coldStartUsesOneFailClosedPortraitEnsureBeforeSessionWork() {
    val start = body(service, "private suspend fun startTicketSessionLocked", "private fun tryReuseActiveHardwareStreamBeforePreflight")
    val ensure = "PhonePortraitLock.ensureVerified(inputRootExecutor)"

    assertEquals(1, start.windowed(ensure.length, 1).count { it == ensure })
    assertTrue(start.contains("if (!$ensure)"))
    assertFalse(start.contains("PhonePortraitLock.force("))
    assertFalse(start.contains("PhonePortraitLock.verify("))
    assertTrue(start.indexOf(ensure) < start.indexOf("TicketPackageSupport.isInstalled"))
    assertTrue(start.indexOf(ensure) < start.indexOf("currentDisplaySize()"))
    assertTrue(start.contains("recordStartupTracePhase(\"portrait_lock_failed\""))
    assertTrue(start.contains("state = \"portrait_lock_failed\""))
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
    assertTrue(recovery.contains("val inAppResult = runInAppUnactivatedRegistrationReset("))
    assertTrue(recovery.contains("return@run inAppResult"))
    assertTrue(recovery.contains("latest_ticket_reselect_in_app_reset_reobserve_started"))
    assertTrue(recovery.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertTrue(recovery.indexOf("if (requireUnactivatedRegistration)") < recovery.indexOf("recordViviHardReset"))
    assertTrue(recovery.indexOf("recordViviHardReset") < recovery.indexOf("launchViviForWake"))
    assertTrue(recovery.contains("wakeStartedAtMillis = observationStartedAtMillis"))
    assertTrue(recovery.contains("recoveryActionRepeatCooldownMillis = LATEST_TICKET_RESELECT_REPEAT_ACTION_COOLDOWN_MILLIS"))
    assertTrue(recovery.contains("LATEST_TICKET_RESELECT_IN_APP_ACTION_GRACE_MILLIS"))
    assertTrue(recovery.contains("LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS"))
    assertTrue(recovery.contains("requireNewTicketRegistration = requireUnactivatedRegistration"))
    assertTrue(recovery.contains("requireLatestTicketSelection = !requireUnactivatedRegistration"))
    assertTrue(recovery.contains("recordLatestTicketReselectRecoveryTelemetry(result)"))
    assertTrue(observation.contains("TicketLatestTicketReselectRecoveryPolicy.remainingMillis("))
    assertTrue(observation.contains("latestTicketSelectionAction"))
    assertTrue(observation.contains("ticketCardDetailActionForHierarchy"))
    assertTrue(observation.contains("ticketDetailProofAccepted("))
    assertTrue(observation.contains("isUpcomingPreValidityTicketDetail"))
    assertTrue(observation.contains("wake_root_latest_ticket_semantic_proved"))
    assertTrue(observation.contains("val canAcceptHealedTicketDetail"))
    assertEquals(
      2,
      Regex("if \\(canAcceptHealedTicketDetail\\)").findAll(observation).count()
    )
    val popupHeal = observation
      .substringAfter("state == TicketViviRecoveryState.CONTROL_CODE_POPUP")
      .substringBefore("state == TicketViviRecoveryState.CONTROL_CODE_RESULT")
    val resultHeal = observation
      .substringAfter("state == TicketViviRecoveryState.CONTROL_CODE_RESULT")
      .substringBefore("val recoveryAction = if")
    assertTrue(popupHeal.substringAfter("if (returnedRaw)").contains("continue"))
    assertTrue(resultHeal.substringAfter("if (healed)").contains("continue"))
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
  fun unactivatedReselectRootedProofSurvivesTransientEmptyAccessibilityHierarchy() {
    val streamProof = body(
      service,
      "private fun noteLatestTicketReselectFreshStreamReady",
      "private fun markLatestTicketReselectTicketDetailObserved"
    )

    // Accessibility may report a fresh detail state while its replacement window has no
    // hierarchy yet. That observation is unavailable evidence, not a conflicting ticket;
    // a fresh conflicting non-empty hierarchy must still reject the rooted proof.
    assertTrue(streamProof.contains("val currentHierarchy = current.hierarchy.orEmpty()"))
    assertTrue(streamProof.contains("currentHierarchy.isNotBlank()"))
    assertTrue(streamProof.contains("val currentHasConflictingFreshObservation"))
    assertTrue(streamProof.contains("if (currentHasConflictingFreshObservation)"))
    assertTrue(streamProof.contains("latestTicketReselectFinalUnactivatedProved"))
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
    assertTrue(publish.contains("emptyTicketInteraction().copy(interactionRevision = cleanRevision)"))
    assertTrue(spacetimeWorker.contains("internal fun emptyTicketInteraction()"))
  }

  @Test
  fun failedSliderStartClearsAStaleClaimAfterStateAlreadyReturnedToReady() {
    val cleanup = body(
      spacetimeWorker,
      "private suspend fun clearFailedTicketSliderClaim",
      "private suspend fun publishInstantTicketSliderApplication"
    )

    assertTrue(cleanup.contains("current.status !in setOf(\"control_active\", \"unactivated_ready\")"))
    assertTrue(cleanup.contains("current.interactionRevision != expectedRevision"))
    assertTrue(cleanup.contains("current.controlId != expectedControlId"))
    assertTrue(cleanup.contains("ownerPublicId = \"\""))
    assertTrue(cleanup.contains("controlId = \"\""))
    assertTrue(cleanup.contains("leasePhase = \"none\""))
    assertTrue(cleanup.contains("latestInputSequence = current.latestInputSequence"))
    assertTrue(cleanup.contains("latestInputPhase = current.latestInputPhase"))
    assertTrue(cleanup.contains("latestProgress = current.latestProgress"))
    assertTrue(cleanup.contains("lastAppliedSequence = current.lastAppliedSequence"))
    assertTrue(cleanup.contains("lastAppliedProgress = current.lastAppliedProgress"))
    assertFalse(cleanup.contains("latestInputSequence = \"0\""))
    assertFalse(cleanup.contains("lastAppliedSequence = \"0\""))
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
    assertTrue(start.contains("ticketSliderGestureBoundsAfterVisualProof("))
    assertTrue(start.contains("reusedGestureBounds"))
    assertTrue(start.contains("beginTicketSliderCaptureBurst(\"ticket_slider_stroke_started\")"))
    assertTrue(start.contains("lastAppliedProgress = 0"))
    assertTrue(start.contains("continueTicketSliderGesture("))
    assertTrue(start.contains("ticketSliderTargetX(bounds, chaseProgress)"))
    assertFalse(start.contains("lastAppliedProgress = initialProgress"))
  }

  @Test
  fun ticketSliderStartUsesOnlyRecentRootedProofWhenAccessibilityIsTransientlyBlank() {
    val start = body(
      service,
      "private suspend fun startTicketSliderInteraction",
      "internal suspend fun applyTicketSliderInteraction"
    )
    assertTrue(start.contains("proof.provedAtUptimeMillis > 0L"))
    assertTrue(start.contains("proof.interactionRevision == cleanRevision"))
    assertTrue(start.contains("TICKET_SLIDER_TRANSIENT_PROOF_MAX_AGE_MILLIS"))
    assertTrue(start.contains("prevalidatedUnactivatedHierarchy"))
    assertTrue(start.contains("val callerProvedHierarchy"))
    assertTrue(
      start.indexOf("val callerProvedHierarchy") <
        start.indexOf("observeAccessibilityViviState(\"ticket_slider_start\")")
    )
    assertTrue(start.contains("val hierarchyUnavailable = hierarchy.isBlank()"))
    assertTrue(start.contains("val conflictingObservation = !hierarchyUnavailable"))
    assertTrue(start.contains("val trustedCachedProof = reusedProof != null"))
    assertTrue(start.contains("!trustedCachedProof && conflictingObservation"))
    assertTrue(start.contains("hierarchyUnavailable && reusedProof == null"))
    assertTrue(start.contains("if (trustedCachedProof)"))
    assertTrue(start.contains("reusedProof.toGraphicBounds()"))
    assertTrue(start.contains("detectFreshTicketRegistrationSliderBounds("))
  }

  @Test
  fun ticketSliderWaitsForAccessibilityToReturnAfterRootedProofBeforeStartingAStroke() {
    val start = body(
      service,
      "private suspend fun startTicketSliderInteraction",
      "internal suspend fun applyTicketSliderInteraction"
    )
    val proof = start.indexOf("if (bounds == null)")
    val reconnect = start.indexOf("PhoneAutomationServiceBridge.awaitAccessibilityConnection(")
    val stroke = start.indexOf("PhoneAutomationServiceBridge.startTicketSliderGesture(")

    assertTrue(service.contains("TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS = 5_000L"))
    assertTrue(reconnect > proof)
    assertTrue(stroke > reconnect)
    assertTrue(start.contains("accessibility_service_unavailable_after_proof"))
    assertEquals(
      stroke,
      start.lastIndexOf("PhoneAutomationServiceBridge.startTicketSliderGesture(")
    )
  }

  @Test
  fun ticketActivationMarksDispatchAfterReconnectAndBeforeTheSingleStroke() {
    val activation = body(
      service,
      "private suspend fun handleInstantTicketSliderActivation",
      "private suspend fun startTicketSliderInteraction"
    )
    val start = body(
      service,
      "private suspend fun startTicketSliderInteraction",
      "internal suspend fun applyTicketSliderInteraction"
    )
    val fresh = activation.indexOf("checkpoint ?: recordTicketActivationFreshProof(")
    val startInteraction = activation.indexOf("val started = startTicketSliderInteraction(")
    val reconnect = start.indexOf("PhoneAutomationServiceBridge.awaitAccessibilityConnection(")
    val unavailable = start.indexOf("accessibility_service_unavailable_after_proof")
    val stroke = start.indexOf("PhoneAutomationServiceBridge.startTicketSliderGesture(")
    val dispatching = start.indexOf("recordTicketActivationDispatching(checkpoint)")

    assertTrue(fresh >= 0)
    assertTrue(startInteraction > fresh)
    assertFalse(activation.contains("recordTicketActivationDispatching("))
    assertTrue(unavailable > reconnect)
    assertTrue(dispatching > unavailable)
    assertTrue(stroke > dispatching)
    assertTrue(
      start.indexOf("recordTicketActivationFreshProof(", stroke) > stroke
    )
    assertTrue(start.contains("TicketSliderGestureStartResult.UNKNOWN"))
    assertTrue(start.contains("ticketActivationCheckpointStore.recordNeedsAttention(checkpoint)"))
    assertTrue(start.contains("accessibility_gesture_start_unknown"))
  }

  @Test
  fun browserResetAndActivateRemainsPhoneOwnedThroughActivatedProof() {
    val command = body(
      service,
      "internal suspend fun handleTicketSpacetimeCommand",
      "internal suspend fun handleTicketSpacetimeDesiredActive"
    )
    val recovery = body(
      service,
      "private suspend fun runLatestTicketReselectRecovery",
      "private suspend fun activateTicketImmediatelyAfterReset"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketImmediatelyAfterReset",
      "private suspend fun runInAppUnactivatedRegistrationReset"
    )
    assertTrue(command.contains("activateAfterReset"))
    assertTrue(command.contains("correlationId"))
    assertTrue(recovery.contains("activateTicketImmediatelyAfterReset("))
    assertTrue(recovery.contains("activated.status != \"activated\""))
    assertTrue(recovery.contains("currentTicketRegistrationProof?.takeIf"))
    assertTrue(recovery.contains("lastTicketRegistrationProof?.takeIf"))
    assertTrue(recovery.contains("it.interactionRevision == cleanInteractionRevision"))
    assertTrue(recovery.contains("rememberTicketRegistrationProof("))
    assertTrue(recovery.contains("status = \"activated\""))
    assertTrue(recovery.contains("activationRevision = activated.activationRevision"))
    assertTrue(recovery.contains("activationAt = activated.activationAt"))
    assertTrue(recovery.contains("streamEpoch = streamEpoch"))
    assertTrue(recovery.contains("frameSequence = frameSequence"))
    assertTrue(recovery.contains("latestTicketReselectRequireUnactivatedRegistration = false"))
    assertTrue(activation.contains("initialProgress = 0"))
    assertTrue(activation.contains("latestInputPhase = \"up\""))
    assertTrue(activation.contains("latestProgress = 10_000"))
  }

  @Test
  fun instantButtonActivationIsProofedPublishedThenAcknowledgedAndReplaySafe() {
    val command = body(
      service,
      "private suspend fun handleInstantTicketSliderActivation",
      "private suspend fun startTicketSliderInteraction"
    )
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun shouldMeasureBrowserCriticalCommand"
    )
    val publish = body(
      spacetimeWorker,
      "private suspend fun publishInstantTicketSliderApplication",
      "private suspend fun publishTicketRegistrationProof"
    )
    assertTrue(command.contains("instantSliderApplicationResults[commandId]"))
    assertTrue(command.contains("inputPhase != \"up\""))
    assertTrue(command.contains("inputSequence <= 0L"))
    assertTrue(command.contains("applyTicketSliderInteraction("))
    assertTrue(command.contains("TicketViviPageEnforcer.isActivatedTicketDetail"))
    assertTrue(command.contains("sliderApplication = application"))
    assertTrue(command.contains("ticketActivationRevision(commandId, cleanRevision, cleanAttemptId)"))
    assertTrue(command.contains("activationRevision = deterministicActivationRevision"))
    assertTrue(command.contains("activationAttemptId = cleanAttemptId"))
    assertTrue(command.contains("prevalidatedUnactivatedHierarchy = currentHierarchy"))
    assertTrue(
      command.indexOf("val currentObservation = observeRootViviStateForWake(") <
        command.indexOf("prevalidatedUnactivatedHierarchy = currentHierarchy")
    )
    assertTrue(
      command.indexOf("if (screen != TicketActivationRecoveryScreen.UNUSED)") <
        command.indexOf("prevalidatedUnactivatedHierarchy = currentHierarchy")
    )
    val publishCall = cycle.indexOf("publishInstantTicketSliderApplication(")
    val deferred = cycle.indexOf("if (!published)", publishCall)
    val acknowledge = cycle.indexOf("client.ack(", deferred)
    assertTrue(publishCall >= 0)
    assertTrue(deferred > publishCall)
    assertTrue(acknowledge > deferred)
    assertTrue(publish.contains("instantSliderPublicationDecision("))
    assertTrue(publish.contains("InstantSliderPublicationDecision.ALREADY_PUBLISHED -> {"))
    assertTrue(publish.contains("InstantSliderPublicationDecision.WAIT_FOR_INTERACTION -> return false"))
    assertTrue(publish.contains("client.updateTicketInteraction("))
  }

  @Test
  fun sliderStartPublishesInteractionBeforeAcknowledgingTheCommand() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    val barrier = cycle.indexOf(
      "command.commandType == \"slider_control_start\" &&\n          result.ok &&\n          result.sliderApplication == null"
    )
    val publication = cycle.indexOf("processTicketSliderInteraction(config, client, interaction)", barrier)
    val acknowledgement = cycle.indexOf("client.ack(", publication)
    assertTrue(barrier >= 0)
    assertTrue(publication > barrier)
    assertTrue(acknowledgement > publication)
    assertTrue(cycle.contains("val interaction = client.ticketInteraction(config)"))
    assertTrue(cycle.contains("interaction != null && ticketInteractionHasUnappliedInput(interaction)"))
    assertTrue(cycle.contains("if (!published) {\n            deferredCommand = true\n            break\n          }"))

    val start = body(
      service,
      "private suspend fun startTicketSliderInteraction",
      "internal suspend fun applyTicketSliderInteraction"
    )
    assertTrue(start.contains("slider_stroke_already_started"))
    assertTrue(start.contains("active.interactionRevision == cleanRevision"))
    assertTrue(start.contains("active.controlId == cleanControlId"))
  }

  @Test
  fun normalSliderApplicationIsRetainedUntilItsDatabaseUpdateSucceeds() {
    val process = body(
      spacetimeWorker,
      "private suspend fun processTicketSliderInteraction",
      "private fun ticketInteractionHasUnappliedInput"
    )
    val retained = body(
      service,
      "internal suspend fun ticketSliderApplicationForPublication",
      "private suspend fun completeTicketSliderInteraction"
    )
    val update = process.indexOf("client.updateTicketInteraction(")
    val confirm = process.lastIndexOf("service.markTicketSliderApplicationPublished(")
    assertTrue(process.contains("service.ticketSliderApplicationForPublication(interaction)"))
    assertTrue(update >= 0)
    assertTrue(confirm > update)
    assertTrue(retained.contains("pendingTicketSliderApplication?.let"))
    assertTrue(retained.contains("return pending.application"))
    assertTrue(retained.contains("pendingTicketSliderApplication = PendingTicketSliderApplication("))
    assertTrue(retained.contains("pending.application.lastAppliedSequence == lastAppliedSequence"))
    assertTrue(retained.contains("pendingTicketSliderApplication = null"))
  }

  @Test
  fun terminalSliderCompletionWaitsLongerThanItsSweepBeforeActivationProof() {
    val completion = body(
      service,
      "private suspend fun completeTicketSliderInteraction",
      "private fun nullResultForSlider"
    )
    assertTrue(service.contains("TICKET_SLIDER_COMPLETION_DURATION_MILLIS = 800L"))
    assertTrue(service.contains("TICKET_SLIDER_GESTURE_TIMEOUT_MILLIS = 1_500L"))
    assertTrue(completion.indexOf("endTicketSliderGesture(") < completion.indexOf("awaitActivatedTicketProof()"))
    assertTrue(completion.indexOf("awaitActivatedTicketProof()") < completion.indexOf("if (!activated)"))
    assertTrue(completion.contains("ticket_slider_completion_callback_unproved"))
    assertTrue(completion.contains("if (!ended)"))
    assertTrue(completion.contains("currentTicketRegistrationProof = null"))
    assertTrue(completion.contains("slider_completion_gesture_failed"))
    assertTrue(completion.contains("activationAttemptId = active.activationAttemptId"))
    assertFalse(completion.contains("ownerPublicId = active.controlId, controlId = active.controlId"))
  }

  @Test
  fun terminalSliderDoesNotRedispatchASecondAcceptedFullStroke() {
    val completion = body(
      service,
      "private suspend fun completeTicketSliderInteraction",
      "private fun nullResultForSlider"
    )
    assertTrue(completion.indexOf("endTicketSliderGesture(") < completion.indexOf("awaitActivatedTicketProof()"))
    assertFalse(completion.contains("retryTicketSliderAfterFreshUnactivatedProof"))
    assertFalse(completion.contains("retryTicketSliderFullStroke"))
    assertTrue(service.contains("awaitActivatedTicketProof()"))
    assertTrue(phoneAutomationBridge.contains("retryTicketSliderFullStroke"))
  }

  @Test
  fun activatedSliderProofAllowsTheRootedHierarchyToSettleWithoutWeakeningItsGates() {
    val proof = body(
      service,
      "private suspend fun awaitActivatedTicketProof",
      "internal suspend fun handleTicketSpacetimeDesiredActive"
    )
    assertTrue(service.contains("TICKET_SLIDER_ACTIVATED_PROOF_TIMEOUT_MILLIS = 16_000L"))
    assertTrue(service.contains("TICKET_ACTIVATED_HIERARCHY_PROOF_TIMEOUT_MILLIS = 5_000L"))
    assertTrue(proof.contains("minOf(TICKET_ACTIVATED_HIERARCHY_PROOF_TIMEOUT_MILLIS"))
    assertFalse(proof.contains("minOf(TICKET_SLIDER_PROOF_TIMEOUT_MILLIS"))
    assertTrue(proof.contains("observation.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(proof.contains("TicketViviPageEnforcer.isActivatedTicketDetail(hierarchy)"))
    assertTrue(proof.contains("verifyFreshTicketDetailVisualProof(\"ticket_slider_activated_visual\")"))
    // Two production rooted reads can each take about 3s including helper cancellation/settling.
    // Both still fit with their activated visual and rooted-frame fallback stages.
    val activatedHierarchyReadTimeoutMillis = 5_000L
    assertTrue(activatedHierarchyReadTimeoutMillis >= 3_000L)
    // A pre-transition hierarchy does not enter visual proof. One complete miss plus the next
    // activated hierarchy/visual/fallback cycle must fit the outer bound.
    val preTransitionThenActivatedProofMillis =
      activatedHierarchyReadTimeoutMillis + activatedHierarchyReadTimeoutMillis + 2_000L + 1_500L
    assertTrue(16_000L >= preTransitionThenActivatedProofMillis)
  }

  @Test
  fun successfulResetPublishesItsExactTerminalProofBeforeAcknowledgement() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun shouldMeasureBrowserCriticalCommand"
    )
    val publish = body(
      spacetimeWorker,
      "private suspend fun publishTicketRegistrationProof",
      "private suspend fun processTicketSliderInteraction"
    )
    val proofAccess = body(
      service,
      "internal fun ticketRegistrationProofForRevision",
      "internal fun ticketStreamEpoch"
    )
    val publishCall = cycle.indexOf("publishTicketRegistrationProof(")
    val deferred = cycle.indexOf("if (!published)", publishCall)
    val acknowledge = cycle.indexOf("client.ack(", deferred)
    assertTrue(publishCall >= 0)
    assertTrue(deferred > publishCall)
    assertTrue(acknowledge > deferred)
    assertTrue(publish.contains("service.ticketRegistrationProofForRevision(cleanRevision)"))
    assertTrue(publish.contains("current.interactionRevision == cleanRevision"))
    assertTrue(publish.contains("client.ticketInteraction(config) ?: return false"))
    assertTrue(proofAccess.contains("currentTicketRegistrationProof?.takeIf"))
    assertTrue(proofAccess.contains("it.interactionRevision == cleanRevision"))
    assertTrue(publish.contains("RESET_TERMINAL_REVISION_REPAIRABLE_STATUSES"))
    assertTrue(publish.contains("current.copy(interactionRevision = cleanRevision)"))
  }

  @Test
  fun freshTicketProofRetainsDurableInputWatermarkForAtomicDatabaseReset() {
    val publish = body(
      spacetimeWorker,
      "private suspend fun publishTicketRegistrationProof",
      "private suspend fun processTicketSliderInteraction"
    )
    assertTrue(publish.contains("if (!proof.hasSliderBounds) return false"))
    assertTrue(publish.contains("publicationBase.hasSliderBounds"))
    assertTrue(publish.contains("latestInputSequence = publicationBase.latestInputSequence"))
    assertTrue(publish.contains("latestInputPhase = publicationBase.latestInputPhase"))
    assertTrue(publish.contains("latestProgress = publicationBase.latestProgress"))
    assertTrue(publish.contains("lastAppliedSequence = publicationBase.lastAppliedSequence"))
    assertTrue(publish.contains("lastAppliedProgress = publicationBase.lastAppliedProgress"))
    assertTrue(publish.contains("published.hasSliderBounds"))
    assertFalse(publish.contains("latestInputSequence = \"0\""))
    assertFalse(publish.contains("lastAppliedSequence = \"0\""))
  }

  @Test
  fun staleUnactivatedProofIsRevalidatedBeforeBrowserCanUseTheSlider() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun shouldMeasureBrowserCriticalCommand"
    )
    val refresh = body(
      spacetimeWorker,
      "private suspend fun refreshStaleTicketRegistrationProof",
      "private fun ticketInteractionHasUnappliedInput"
    )
    val serviceRefresh = body(
      service,
      "internal suspend fun refreshUnactivatedTicketRegistrationProofForCurrentStream",
      "internal fun ticketStreamEpoch"
    )
    val maintenance = cycle
      .substringAfter("val interactionMaintenance = interactionMaintenanceState.plan(")
      .substringBefore("val startOwnedByControlCode")
    assertTrue(maintenance.contains("if (interactionMaintenance.refreshRegistrationProof)"))
    assertTrue(maintenance.contains("val proofMaintenance = refreshStaleTicketRegistrationProof(config, client, interaction)"))
    assertTrue(maintenance.contains("sliderProofFollowUp = proofMaintenance.followUp"))
    assertTrue(
      maintenance.indexOf("sliderFollowUp = processTicketSliderInteraction") <
        maintenance.indexOf("refreshStaleTicketRegistrationProof(config, client, interaction)")
    )
    assertTrue(refresh.contains("ticketRegistrationProofRequiresRefresh("))
    assertTrue(refresh.contains("publishStaleTicketRegistrationProofNeedsAttention("))
    assertTrue(refresh.contains("publishTicketRegistrationProof(config, client, revision)"))
    assertTrue(refresh.contains("completed = false"))
    assertTrue(refresh.contains("completed = published"))
    assertTrue(refresh.contains("status != \"unactivated_ready\""))
    assertTrue(refresh.contains("interactionRevision != interactionRevision"))
    assertTrue(serviceRefresh.contains("observeTicketDetailForWakeWithRoot("))
    assertTrue(serviceRefresh.contains("requireUnactivatedRegistration = true"))
    assertTrue(serviceRefresh.contains("finalSliderBounds"))
    assertTrue(serviceRefresh.contains("rememberTicketRegistrationProof("))
    assertTrue(serviceRefresh.contains("stream_epoch_slider_proof_refresh"))
  }

  @Test
  fun interactionMaintenanceIsLocallyGatedAndReusesOneSnapshotAfterCommands() {
    val connection = body(
      spacetimeWorker,
      "private fun onSpacetimeClientConnected",
      "private suspend fun runCycle"
    )
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    val maintenance = cycle
      .substringAfter("val interactionMaintenance = interactionMaintenanceState.plan(")
      .substringBefore("val startOwnedByControlCode")
    val sliderPublication = body(
      spacetimeWorker,
      "private suspend fun processTicketSliderInteraction",
      "private suspend fun refreshStaleTicketRegistrationProof"
    )
    val proofRefresh = body(
      spacetimeWorker,
      "private suspend fun refreshStaleTicketRegistrationProof",
      "private suspend fun publishStaleTicketRegistrationProofNeedsAttention"
    )
    val pendingSliderState = body(
      service,
      "internal fun ticketSpacetimeSliderApplicationPending",
      "internal fun takeTicketRegistrationProof"
    )

    assertTrue(connection.contains("interactionMaintenanceState.onClientConnected()"))
    assertTrue(cycle.indexOf("for (scannedCommand in commands)") < cycle.indexOf("val interactionMaintenance"))
    assertTrue(cycle.indexOf("service.handleTicketSpacetimeCommand(command)") < cycle.indexOf("val interactionMaintenance"))
    assertTrue(cycle.contains("ticketSpacetimeCommandInvalidatesInteractionMaintenance(command.commandType)"))
    assertTrue(cycle.contains("interactionMaintenanceState.invalidateRegistrationProofCheck()"))
    assertTrue(maintenance.contains("streamEpoch = service.ticketStreamEpoch()"))
    assertTrue(maintenance.contains("streamFrameSequence = service.ticketFrameSequence()"))
    assertTrue(maintenance.contains("sliderActive = service.ticketSpacetimeSliderActive()"))
    assertTrue(maintenance.contains("sliderApplicationPending = service.ticketSpacetimeSliderApplicationPending()"))
    assertTrue(maintenance.contains("if (interactionMaintenance.interactionQueryRequired)"))
    assertEquals(1, Regex("client\\.ticketInteraction\\(config\\)").findAll(maintenance).count())
    assertTrue(maintenance.contains("recordMaintenanceResult("))
    assertTrue(maintenance.contains("interactionSnapshotAvailable = interaction != null"))
    assertTrue(maintenance.contains("registrationProofCompleted = registrationProofCompleted"))
    assertTrue(maintenance.contains("refreshStaleTicketRegistrationProof(config, client, interaction)"))
    assertTrue(maintenance.contains("processTicketSliderInteraction(config, client, interaction)"))
    assertTrue(maintenance.contains("if (interactionMaintenance.publishSliderApplication)"))
    assertTrue(maintenance.contains("if (sliderFollowUp)"))
    assertTrue(maintenance.contains("interactionMaintenanceState.invalidateRegistrationProofCheck()"))
    assertTrue(maintenance.contains("if (!sliderFollowUp && interactionMaintenance.inspectRegistrationProof)"))
    assertTrue(
      maintenance.indexOf("processTicketSliderInteraction(config, client, interaction)") <
        maintenance.indexOf("refreshStaleTicketRegistrationProof(config, client, interaction)")
    )
    assertTrue(
      maintenance.indexOf("refreshStaleTicketRegistrationProof(config, client, interaction)") <
        maintenance.indexOf("recordMaintenanceResult(")
    )
    assertTrue(
      maintenance.indexOf("processTicketSliderInteraction(config, client, interaction)") <
        maintenance.indexOf("recordMaintenanceResult(")
    )
    assertFalse(sliderPublication.contains("client.ticketInteraction(config)"))
    assertFalse(proofRefresh.contains("client.ticketInteraction(config)"))
    assertTrue(pendingSliderState.contains("synchronized(ticketSliderStateLock)"))
    assertTrue(pendingSliderState.contains("pendingTicketSliderApplication != null"))
  }

  @Test
  fun staleProofFailureOpensTheSafeResetRecoveryState() {
    val failure = body(
      spacetimeWorker,
      "private suspend fun publishStaleTicketRegistrationProofNeedsAttention",
      "private fun ticketInteractionHasUnappliedInput"
    )
    assertTrue(failure.contains("current.status != \"unactivated_ready\""))
    assertTrue(failure.contains("status = \"needs_attention\""))
    assertTrue(failure.contains("leasePhase = \"none\""))
    assertTrue(failure.contains("latestInputSequence = \"0\""))
    assertTrue(failure.contains("lastAppliedSequence = \"0\""))
  }

  @Test
  fun scheduledResetStillFlowsThroughTerminalProofPublication() {
    val schedule = body(
      service,
      "private suspend fun runLatestTicketReselectRecovery",
      "private suspend fun activateTicketImmediatelyAfterReset"
    )
    val workerCycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun commandUsesLegacyRegistrationInteraction"
    )
    assertTrue(schedule.contains("beginLatestTicketReselectStreamProof"))
    assertTrue(schedule.contains("rememberTicketRegistrationProof("))
    assertTrue(workerCycle.contains("activationRefreshRevision"))
    assertTrue(workerCycle.contains("publishTicketRegistrationProof("))
    assertFalse(spacetimeWorker.contains("ensurePendingTicketActivationSchedule"))
    assertFalse(spacetimeWorker.contains("ticketremote_schedule_activation_expiry_reset"))
  }

  @Test
  fun resetTerminalProofRepairsOnlyAbandonedResetRevisions() {
    val repairable = body(
      spacetimeWorker,
      "private val RESET_TERMINAL_REVISION_REPAIRABLE_STATUSES",
      "private data class TicketSpacetimeConfig"
    )
    listOf("reset_queued", "preparing", "needs_attention", "failed").forEach {
      assertTrue(repairable.contains("\"$it\""))
    }
    listOf("control_active", "activated", "unactivated_ready").forEach {
      assertFalse(repairable.contains("\"$it\""))
    }
  }

  @Test
  fun instantActivationSweepsTheWholeTrackAtAFlutterReliableCadence() {
    val instant = body(
      service,
      "private suspend fun handleInstantTicketSliderActivation",
      "private suspend fun startTicketSliderInteraction"
    )
    val resetActivation = body(
      service,
      "private suspend fun activateTicketImmediatelyAfterReset",
      "private suspend fun runInAppUnactivatedRegistrationReset"
    )
    assertTrue(instant.contains("val started = startTicketSliderInteraction("))
    assertTrue(instant.contains("initialProgress = 0"))
    assertTrue(instant.contains("activationCommandId = commandId"))
    assertTrue(resetActivation.contains("initialProgress = 0"))
    assertTrue(service.contains("TICKET_SLIDER_COMPLETION_DURATION_MILLIS = 800L"))
    assertTrue(phoneAutomationAccessibilityService.contains("durationMillis.coerceIn(32L, 1_000L)"))
  }

  @Test
  fun activatedVisualFallbackRequiresExactHierarchyAndANewerSameEpochRootedFrame() {
    val proof = body(
      service,
      "private suspend fun awaitActivatedTicketProof",
      "internal suspend fun handleTicketSpacetimeDesiredActive"
    )
    val fallback = body(
      service,
      "private suspend fun verifyFreshRootedFrameAfterActivatedHierarchy",
      "internal suspend fun handleTicketSpacetimeDesiredActive"
    )
    val exactHierarchy = proof.indexOf("TicketViviPageEnforcer.isActivatedTicketDetail(hierarchy)")
    val fallbackCall = proof.indexOf("verifyFreshRootedFrameAfterActivatedHierarchy(")
    assertTrue(exactHierarchy >= 0)
    assertTrue(fallbackCall > exactHierarchy)
    assertTrue(proof.contains("observation.state == TicketViviRecoveryState.TICKET_DETAIL"))
    assertTrue(fallback.contains("streamEpoch == expectedEpoch"))
    assertTrue(fallback.contains("frameSequence > afterFrameSequence"))
    assertTrue(fallback.contains("activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264"))
    assertTrue(fallback.contains("hardwareCaptureVerified"))
    assertTrue(fallback.contains("rootHardwareH264CaptureEngine.snapshot(nowMillis).active"))
    assertTrue(fallback.contains("freshFrameAgeMillis <= TICKET_ACTIVATED_ROOTED_FRAME_MAX_AGE_MILLIS"))
  }

  @Test
  fun recoveryProvedUnactivatedResetStillRunsRequestedActivation() {
    val recovery = body(
      service,
      "private suspend fun runLatestTicketReselectRecovery",
      "private suspend fun activateTicketImmediatelyAfterReset"
    )
    assertTrue(recovery.contains("latestTicketReselectFinalUnactivatedProved"))
    assertTrue(recovery.contains("val inAppResult = runInAppUnactivatedRegistrationReset("))
    assertTrue(recovery.contains("if (inAppResult.success)"))
    assertTrue(recovery.contains("latest_ticket_reselect_in_app_reset_reobserve_started"))
    assertTrue(recovery.contains("observeTicketDetailForWakeWithRoot("))
    assertTrue(recovery.contains("requireUnactivatedRegistration = requireUnactivatedRegistration"))
    assertTrue(recovery.contains("val terminalTicketReady = result.success ||"))
    assertTrue(recovery.contains("requireUnactivatedRegistration && finalUnactivatedReady"))
    assertTrue(recovery.contains("if (!terminalTicketReady)"))
    assertTrue(recovery.contains("if (requireUnactivatedRegistration && activateAfterReset)"))
    assertFalse(recovery.contains("if (result.success && requireUnactivatedRegistration && activateAfterReset)"))
    assertTrue(recovery.contains("if (terminalTicketReady)"))
  }

  @Test
  fun browserCriticalResetAndSliderActionsUseNoGenericDarkClampTail() {
    val reset = body(
      service,
      "private suspend fun runInAppUnactivatedRegistrationReset",
      "private fun recordLatestTicketReselectRecoveryTelemetry"
    )
    val recoveryInput = body(
      service,
      "private suspend fun runFastRecoveryInput",
      "private suspend fun runFastNonTouchWakeScript"
    )
    val accessibility = source("phoneautomation/PhoneAutomationAccessibilityService.kt")
    assertTrue(reset.contains("zeroTailPanelClamp = true"))
    assertTrue(recoveryInput.contains("postMillis = 0L"))
    assertTrue(recoveryInput.contains("if (zeroTailPanelClamp) 0L"))
    assertTrue(accessibility.contains("clearNonTouchInputTailForBrowserCriticalAction(reason)"))
    assertTrue(recoveryInput.contains("clearNonTouchInputTailForBrowserCriticalAction"))
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
    assertTrue(complete.contains("\"ticket_slider_completion_finished\""))
    assertTrue(complete.contains("\"ticket_slider_completion_gesture_failed\""))
    assertTrue(service.contains("startControlCodeRequestBurst(reason)"))
    assertTrue(service.contains("stopControlCodeRequestBurst(reason)"))
  }

  @Test
  fun latestTicketReselectCommandRemainsPendingUntilTerminalPhoneState() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private fun shouldMeasureBrowserCriticalCommand"
    )
    val measuredCommands = body(
      spacetimeWorker,
      "private fun shouldMeasureBrowserCriticalCommand",
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
    assertTrue(measuredCommands.contains("commandType == \"keyframe\""))
    assertTrue(measuredCommands.contains("commandType == \"force_ticket_reselect\""))
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
      "private fun shouldMeasureBrowserCriticalCommand"
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
    assertTrue(commandPriority.contains("\"force_ticket_reselect\", \"reset_ticket_registration\", \"slider_control_start\" -> 2"))
    assertTrue(commandPriority.contains("\"keyframe\", \"recover_stream\", \"start\" -> 3"))
    // One fetch occurs on each mutually exclusive eager/signaled branch; there is no
    // second pre-handle fetch inside the command loop.
    assertEquals(2, Regex("client\\.pendingCommands\\(config\\)").findAll(cycle).count())
    val commandLoop = cycle.substring(cycle.indexOf("for (scannedCommand in commands)"))
    assertFalse(commandLoop.contains("client.pendingCommands(config)"))
    assertTrue(cycle.contains("pixel_direct_command_received"))
    assertTrue(cycle.contains("pixel_direct_command_handler_finished"))
    assertTrue(cycle.contains("pixel_direct_command_committed"))
    assertTrue(cycle.contains("databaseToPhoneMillis"))
    assertTrue(cycle.contains("browserCriticalCommandStartedAtMillis"))
    assertTrue(cycle.contains("result.terminal"))
  }

  @Test
  fun startCommandIsRevalidatedImmediatelyBeforeReceiptAndDispatch() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun reconcileLatestTicketReselectCommand"
    )
    val commandLoop = cycle.substring(cycle.indexOf("for (scannedCommand in commands)"))
    val revalidate = commandLoop.indexOf("client.pendingCommandIsDispatchable(config, command.id)")
    val receipt = commandLoop.indexOf("service.noteStartupStartCommandReceived(")
    val dispatch = commandLoop.indexOf("service.handleTicketSpacetimeCommand(command)")
    val ack = commandLoop.indexOf("client.ack(")

    assertTrue(revalidate >= 0)
    assertTrue(receipt > revalidate)
    assertTrue(dispatch > receipt)
    assertTrue(ack > dispatch)
    assertTrue(commandLoop.substring(revalidate, receipt).contains("continue"))
    assertTrue(commandLoop.substring(revalidate, receipt).contains("Instant.now()"))
    assertTrue(cycle.contains("!staleStartCommandSkipped"))
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
      "private fun shouldMeasureBrowserCriticalCommand"
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
  fun expiredRegistrationReselectCarriesRevisionAndRepairsDurableInteraction() {
    val dispatch = body(
      service,
      "internal suspend fun handleTicketSpacetimeCommand",
      "private suspend fun handleInstantTicketSliderActivation"
    )
    val expiryResetDispatch = dispatch
      .substringAfter("\"force_ticket_reselect\" ->")
      .substringBefore("\"reset_ticket_registration\" ->")
    val absentState = source("ticket/TicketStreamService.kt")
    val reconciliation = body(
      spacetimeWorker,
      "private suspend fun reconcileLatestTicketReselectCommand",
      "private fun shouldMeasureBrowserCriticalCommand"
    )
    val attention = body(
      spacetimeWorker,
      "private suspend fun publishTicketInteractionNeedsAttention",
      "private suspend fun clearFailedTicketSliderClaim"
    )

    assertTrue(dispatch.contains("interactionRevision = command.revision.takeIf { activationExpiryReset }.orEmpty()"))
    assertTrue(expiryResetDispatch.contains("requireUnactivatedRegistration = activationExpiryReset"))
    assertFalse(expiryResetDispatch.contains("activateAfterReset"))
    assertTrue(absentState.contains("TicketLatestTicketReselectAbsentState"))
    assertTrue(absentState.contains("latestTicketReselectInteractionRevision"))
    assertTrue(reconciliation.contains("publishTicketRegistrationProof("))
    assertTrue(reconciliation.contains("publishTicketInteractionNeedsAttention("))
    assertTrue(reconciliation.contains("details(\"outcomeCategory\" to \"unactivated_ready\")"))
    assertTrue(attention.contains("RESET_TERMINAL_REVISION_REPAIRABLE_STATUSES"))
    assertTrue(attention.contains("interactionRevision = cleanRevision.ifBlank { current.interactionRevision }"))
    assertTrue(attention.contains("ownerPublicId = \"\""))
    assertTrue(attention.contains("leasePhase = \"none\""))
    assertTrue(attention.contains("latestInputSequence = current.latestInputSequence"))
    assertTrue(attention.contains("latestInputPhase = current.latestInputPhase"))
    assertTrue(attention.contains("latestProgress = current.latestProgress"))
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
    assertTrue(recovery.contains("if (zeroTailPanelClamp)"))
    assertTrue(recovery.contains("wrapNonTouchPanelSleepClamp(command, postMillis = 0L"))
    assertTrue(recovery.contains("recoveryInputRootExecutor.run(command, timeout)"))
  }

  @Test
  fun staleInactivityStopCannotTearDownAReactivatedSession() {
    val stop = body(service, "private suspend fun stopTicketSessionIfStillInactive", "private suspend fun stopTicketSessionLocked")
    assertTrue(stop.contains("viewerInputGeneration == expectedViewerInputGeneration"))
    assertTrue(stop.contains("lastViewerInputAtMillis == expectedLastInputAtMillis"))
    assertTrue(stop.contains("TicketInactivityPolicy.shouldStop"))
    assertTrue(stop.contains("activeViewerDemand = videoClients.isNotEmpty()"))
    assertTrue(stop.contains("if (!authorized)"))
  }

  @Test
  fun activeVideoViewerRetainsInactivityLeaseWhileDisconnectStillUsesBoundedCleanup() {
    val timer = body(service, "private fun ensureInactivityTimer", "private suspend fun suppressBlackoutOverlayForRemote")
    val accept = body(service, "private suspend fun acceptWebSocket", "private suspend fun startTicketSessionForVideoClientOpen")
    val disconnect = body(service, "private fun scheduleClientDisconnectGraceLocked", "private fun markViewerInput")

    assertTrue(timer.contains("TicketInactivityPolicy.shouldRetain"))
    assertTrue(timer.contains("activeViewerDemand = videoClients.isNotEmpty()"))
    assertTrue(timer.contains("lastViewerInputAtMillis = nowMillis"))
    assertTrue(timer.contains("viewerInputGeneration += 1L"))
    assertTrue(accept.contains("if (totalClientCount() == 0)"))
    assertTrue(accept.contains("scheduleClientDisconnectGraceLocked()"))
    assertTrue(disconnect.contains("totalClientCount() == 0"))
    assertTrue(disconnect.contains("streamStartAdmission.claimCount() == 0L"))
    assertTrue(disconnect.contains("CLIENT_DISCONNECT_IDLE_GRACE_MILLIS"))
    assertTrue(disconnect.contains("noteClientDetachedLocked(\"browser_left_ticket_screen\")"))
  }

  @Test
  fun staleDisconnectJobCannotStopAReplacementGeneration() {
    val disconnect = body(service, "private fun scheduleClientDisconnectGraceLocked", "private fun markViewerInput")
    assertTrue(disconnect.contains("val runningJob = coroutineContext[Job]"))
    assertTrue(disconnect.contains("clientDisconnectStopJob !== runningJob"))
    assertTrue(disconnect.contains("totalClientCount() == 0"))
    assertTrue(disconnect.contains("ticketSessionOpen()"))
    assertTrue(disconnect.contains("streamStartAdmission.claimCount() == 0L"))
  }

  @Test
  fun finalControlClaimReleaseRestoresNoClientDisconnectCleanupUnderSessionLock() {
    val claim = body(
      service,
      "private suspend fun claimControlCodeAutomationForRequest",
      "private suspend fun releaseControlCodeAutomationForRequest"
    )
    val release = body(
      service,
      "private suspend fun releaseControlCodeAutomationForRequest",
      "private fun recordControlCodeCommandEnvelope"
    )

    assertTrue(claim.contains("streamStartAdmission.claim()"))
    assertTrue(claim.contains("clientDisconnectStopJob?.cancel()"))
    assertTrue(release.contains("val remainingClaims = streamStartAdmission.release()"))
    assertTrue(release.contains("withContext(NonCancellable)"))
    assertTrue(release.contains("sessionMutex.withLock"))
    assertTrue(release.contains("val currentClaims = streamStartAdmission.claimCount()"))
    assertTrue(release.contains("scheduleClientDisconnectGraceLocked()"))
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
