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
  private val tsf3Envelope by lazy { source("ticket/TicketTsf3FrameEnvelope.java") }
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
  fun streamRecoveryRechecksStartupInsideTheExistingEncoderOwner() {
    val restart = body(service, "private fun restartActiveStreamEngine", "private fun scheduleStreamWatchdog")
    assertTrue(restart.contains("TicketStreamStartupRecoveryPolicy.restartIfNeeded("))
    assertTrue(restart.contains("lock = encoderLock"))
    assertTrue(restart.contains("streamActive && !activeHardwareStreamStartingForRecovery(SystemClock.elapsedRealtime())"))
    assertTrue(restart.indexOf("shouldRestart =") < restart.indexOf("resetFrameEpoch("))
    assertTrue(restart.indexOf("restart = {") < restart.indexOf("rootHardwareH264CaptureEngine.restart(reason)"))
    assertTrue(restart.indexOf("rootHardwareH264CaptureEngine.restart(reason)") < restart.indexOf("ensureRootHardwareH264CaptureIfPossible()"))
  }

  @Test
  fun delayedSourceCallbacksAreFencedBeforeCurrentEpochAndCounters() {
    val receive = body(service, "private fun handleRootHardwareH264CaptureFrame", "private fun scheduleRootHardwareSecureCaptureProbe")
    val lock = receive.indexOf("val acceptedGeneration = synchronized(encoderLock)")
    val fence = receive.indexOf("rootHardwareH264CaptureEngine.isCurrentCaptureGeneration(frame)")
    assertTrue(lock >= 0)
    assertTrue(fence > lock)
    assertTrue(fence < receive.indexOf("droppedVideoFrames += 1L"))
    assertTrue(fence < receive.indexOf("ensureFrameEpoch(\"frame\")"))
    assertTrue(fence < receive.indexOf("encodedFrames += 1"))
    assertTrue(h264Engine.contains("captureGeneration = sourceGeneration"))
    assertTrue(h264Engine.contains("frame.hasCurrentCaptureGeneration(captureGeneration.get())"))
  }

  @Test
  fun v3TicketActionsUseVisualProofAndOnlyTheBoundedRegistrationHierarchy() {
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
    val normalizedRevision = activation.indexOf(
      "val revision = exactRegistrationProof?.interactionRevision ?: command.revision"
    )
    val freshProofCheckpoint = activation.indexOf(
      "recordTicketActivationFreshProof(command.id, revision, request.attemptId)"
    )
    assertTrue(normalizedRevision >= 0)
    assertTrue(freshProofCheckpoint > normalizedRevision)
    assertFalse(executor.contains("TicketViviPageEnforcer"))
    assertFalse(executor.contains("observeRootViviState"))
    assertTrue(executor.contains("observation.backBounds"))
    assertFalse(executor.contains("input keyevent KEYCODE_BACK"))
    assertTrue(activation.contains("TicketViviPageEnforcer.ticketRegistrationSliderBoundsForHierarchy"))
    assertTrue(service.contains("PhoneAutomationServiceBridge.snapshotTicketRegistrationNodes"))
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
  fun noTransitionRetryStaysInsideTheSameActionAndUsesOneFinalizer() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun publishNextPhoneResult"
    )
    assertTrue(spacetimeWorker.contains("client.finalizeTicketActionV3(config, envelope)"))
    assertFalse(cycle.contains("client.finalizeTicketActionV3(config, envelope)"))
    assertTrue(spacetimeWorker.contains("service.completeTicketVisualActionFinalization(envelope)"))
    assertEquals(
      1,
      Regex("client\\.finalizeTicketActionV3\\(config, envelope\\)")
        .findAll(spacetimeWorker)
        .count()
    )
    assertFalse(cycle.contains("retryTicketActionV3AfterNoTransition"))
    assertFalse(spacetimeWorker.contains("ticketremote_retry_ticket_action_v3_after_no_transition"))
  }

  @Test
  fun nativeEdgeCropIsSharedByEncodingVisibilityAndVisualClassification() {
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
    assertFalse(h264Main.contains("motionSampler"))
    assertTrue(h264Main.contains("visible = frameLooksVisible(source.bitmap, sourceCrop)"))
    assertTrue(h264Main.contains("classifyControlCodeVisualState(\n              source.bitmap,\n              sourceCrop,"))
    assertTrue(h264Main.contains("new PendingCapture(source, sourceCrop,"))
    assertTrue(h264Main.contains("drawBitmap(inputSurface, packet.source.bitmap, packet.sourceCrop, destination, paint)"))
    assertEquals(2, Regex("Rect sourceCrop = sourceCropRect\\(").findAll(h264Main).count())
    assertEquals(1, Regex("return new Rect\\(").findAll(h264Main).count())
  }

  @Test
  fun proveCurrentUsesTwoFreshVisualFramesWithoutLaunchingOrNavigating() {
    val dispatch = body(
      service,
      "private suspend fun handleTicketVisualActionV3",
      "private suspend fun runReadOnlyTicketVisualProofV3"
    )
    val action = body(
      service,
      "private suspend fun runReadOnlyTicketVisualProofV3",
      "private suspend fun runTicketVisualActionV3"
    )
    val proof = body(
      service,
      "private suspend fun proveCurrentTicketVisualAction",
      "private suspend fun activateTicketFromVisualAction"
    )
    assertTrue(action.contains("awaitStableTicketVisualActionObservation"))
    assertTrue(action.contains("currentOnly = true"))
    assertTrue(h264Engine.contains("requestTicketCurrentVisualProbe"))
    assertTrue(h264Main.contains("TicketVisualActionClassifier.classifyCurrent(pixels)"))
    assertTrue(h264Main.contains("TicketVisualActionClassifier.currentVisualDiagnostic(pixels)"))
    assertTrue(action.contains("proveCurrentTicketVisualAction(command, request, current)"))
    assertTrue(action.contains("ticketActionV3MutationGeneration"))
    assertTrue(action.contains("currentRootPhysicalTouchState"))
    assertFalse(action.contains("TicketActionPanelDarkLease"))
    assertFalse(action.contains("controlCodePhoneMutationLane"))
    assertFalse(action.contains("launchViviForWake"))
    assertFalse(action.contains("tapTicketVisualProbeBounds"))
    assertTrue(dispatch.contains("if (ticketActionV3Job?.isActive == true)"))
    assertTrue(dispatch.contains("ticket_action_v3_phone_lane_busy"))
    assertFalse(dispatch.contains("explicitActionSupersedesProof"))
    assertFalse(dispatch.contains("priorJob?.cancel()"))
    assertFalse(dispatch.contains("priorJob?.join()"))
    assertTrue(proof.contains("TicketVisualPhoneState.UNACTIVATED_DETAIL"))
    assertTrue(proof.contains("ticket_action_current_unactivated_proved"))
    assertTrue(proof.contains("command.revision.isBlank()"))
    assertFalse(proof.contains("selectedAnchor"))
    assertFalse(proof.contains("launchViviForWake"))
    assertFalse(proof.contains("tapTicketVisualProbeBounds"))
  }

  @Test
  fun staleControlCleanupCheckpointUsesAProtectedFreshPixelOnlyProveCurrentRecovery() {
    val dispatch = body(
      service,
      "private suspend fun handleTicketVisualActionV3",
      "private suspend fun runReadOnlyTicketVisualProofV3"
    )
    val proof = body(
      service,
      "private suspend fun runReadOnlyTicketVisualProofV3",
      "private suspend fun runTicketVisualActionV3"
    )
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )

    assertTrue(dispatch.contains("cleanupCheckpointRecoveryProof"))
    assertTrue(dispatch.contains("controlCodeSignatureCleanupRequired"))
    assertTrue(dispatch.contains("if (ticketActionV3Job?.isActive == true)"))
    assertTrue(dispatch.contains("cleanupCheckpointRecoveryProof\n      ) {\n        ticketActionV3MutationGeneration += 1L"))
    assertTrue(dispatch.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(dispatch.contains("readOnlyCleanupCheckpointRecovery = cleanupCheckpointRecoveryProof"))
    assertTrue(wrapper.contains("readOnlyCleanupCheckpointPendingInsideLane"))
    assertTrue(wrapper.contains("recoveringControlCodeCheckpointAtStart"))
    assertTrue(wrapper.contains("runReadOnlyTicketVisualProofV3("))
    assertTrue(wrapper.contains("requireFreshRawDetail = readOnlyCleanupCheckpointPendingInsideLane"))
    assertTrue(proof.contains("verifyFreshTicketDetailVisualProof("))
    assertTrue(proof.contains("probeWaitMillis = CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS"))
    assertTrue(proof.contains("ticketControlCodeFreshRawDetailMismatch(current)"))
    assertTrue(proof.contains("capture_generation_changed"))
    assertTrue(proof.contains("currentOnly = true"))
    assertFalse(proof.contains("TicketViviPageEnforcer"))
    assertFalse(proof.contains("dumpViviHierarchy"))
    assertFalse(proof.contains("launchViviForWake"))
    assertFalse(proof.contains("tapTicketVisualProbeBounds"))
    assertTrue(wrapper.contains("provisional.semanticProof && provisional.actionId == request.actionId"))
    assertTrue(wrapper.contains("!controlCodeRequestActive()"))
    assertTrue(wrapper.contains("pendingControlCodeBrowserCaptureRequestId == null"))
    assertTrue(wrapper.contains("activeControlCodeKeyboardClamp == null"))
    assertTrue(wrapper.contains("activeTicketActionPanelDarkLease == null"))
    assertTrue(wrapper.indexOf("panelLease.releaseAfterFinalConvergence") <
      wrapper.indexOf("commitControlCodeCleanStateAfterPanelFinalization"))
    assertTrue(terminal.contains("ticketVisualActionCaptureLeaseActive"))
  }

  @Test
  fun v3ActionClampCoversExecutionAndCleansUpOnEveryExit() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    assertTrue(wrapper.contains("newTicketPanelDarkLease(request.actionId)"))
    val factory = body(service, "private fun newTicketPanelDarkLease", "private val panelDarkCommandRunner")
    assertTrue(factory.contains("ownerProcessId = android.os.Process.myPid()"))
    assertTrue(factory.contains("physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState"))
    assertTrue(wrapper.indexOf("panelLease.acquire()") <
      wrapper.indexOf("runTicketVisualActionV3WithCaptureLease("))
    assertTrue(wrapper.contains("finally"))
    assertTrue(wrapper.contains("withContext(NonCancellable)"))
    assertTrue(wrapper.contains(
      "panelLease.releaseAfterFinalConvergence(\"ticket_action_terminal\")"
    ))
    assertTrue(wrapper.contains("ticket_action_physical_touch_preempted_after_dispatch"))
    assertTrue(panelDarkLease.contains("physical_touch_at_mutation_boundary"))
    assertTrue(panelDarkLease.contains("suspend fun beforeMutationAllowed()"))
    assertTrue(panelDarkLease.contains("refreshStaleZeroProofAtMutationBoundary()"))
    val mutationGate = body(panelDarkLease, "suspend fun beforeMutationAllowed()", "private fun physicalTouchClearAtMutationBoundary()")
    assertTrue(Regex(
      "!physicalTouchClearAtMutationBoundary\\(\\)"
    ).findAll(mutationGate).count() >= 3)
    assertTrue(panelDarkLease.contains("/proc/\${'$'}owner_pid/stat"))
    assertTrue(panelDarkLease.contains("CLEANUP_STALE_HELPERS_SCRIPT"))
    assertTrue(config.contains("val launchExitCode: Int? = null"))
    assertTrue(config.contains("val launchDurationMillis: Long? = null"))
    assertTrue(config.contains("val lastVerifierClassification: String = \"not_run\""))
    assertTrue(config.contains("val lastVerifierExitCode: Int? = null"))
    assertTrue(config.contains("val lastVerifierDurationMillis: Long? = null"))
    assertTrue(config.contains("val helperStage: String = \"not_observed\""))
    assertTrue(config.contains("val helperExitCode: Int? = null"))
    assertTrue(service.contains("launchExitCode = lease.launchExitCode"))
    assertTrue(service.contains("launchDurationMillis = lease.launchDurationMillis"))
    assertTrue(service.contains(
      "lastVerifierClassification = lease.lastVerifierClassification"
    ))
    assertTrue(service.contains("lastVerifierExitCode = lease.lastVerifierExitCode"))
    assertTrue(service.contains("lastVerifierDurationMillis = lease.lastVerifierDurationMillis"))
    assertTrue(service.contains("helperStage = lease.helperStage"))
    assertTrue(service.contains("helperExitCode = lease.helperExitCode"))
  }

  @Test
  fun controlCodeOwnsTheRawPanelDarkLeaseBeforeKeyboardOrPhoneMutation() {
    val request = body(
      service,
      "private suspend fun handleGenerateControlCode(",
      "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr"
    )
    val lane = request.substringAfter("controlCodePhoneMutationLane.withOwnership {")
    assertTrue(lane.contains("newTicketPanelDarkLease(panelOwnerId)"))
    assertTrue(lane.contains("panelDarkLease?.acquire()"))
    assertTrue(lane.indexOf("panelDarkLease?.acquire()") <
      lane.indexOf("RequestScopedKeyboardClampLease("))
    assertTrue(lane.indexOf("panelDarkLease?.acquire()") <
      lane.indexOf("ensureControlCodeRequestPreflight("))
    val laneStart = request.indexOf("controlCodePhoneMutationLane.withOwnership {")
    val outerFinally = request.lastIndexOf("\n    } finally {")
    val laneKeyboardRelease = request.indexOf(
      "keyboardClampReleased = keyboardClampLease?.release(",
      laneStart
    )
    val lanePanelTailRelease = request.indexOf(
      "panelDarkLease?.releaseAfterFinalConvergence(\"control_code_terminal\")",
      laneStart
    )
    val readyPublication = request.indexOf(
      "publishControlCodeReadyAfterPanelFinalization(cleanRequestId)",
      lanePanelTailRelease
    )
    assertTrue(laneStart >= 0)
    assertTrue(outerFinally > laneStart)
    assertTrue(laneKeyboardRelease in laneStart until outerFinally)
    assertTrue(lanePanelTailRelease in laneKeyboardRelease until outerFinally)
    assertTrue(readyPublication in lanePanelTailRelease until outerFinally)
    assertFalse(request.substring(outerFinally).contains(
      "keyboardClampReleased = keyboardClampLease?.release("
    ))
    assertFalse(request.substring(outerFinally).contains("panelDarkLease?.release("))
  }

  @Test
  fun everyRootInputAndWakeUsesTheSharedPanelDarkCommandBoundary() {
    listOf(
      "private suspend fun runFastNonTouchInput",
      "private suspend fun runSensitiveFastNonTouchScript",
      "private suspend fun runFastOneShotControlSurfaceCloseInput",
      "private suspend fun runFastRecoveryInput",
      "private suspend fun runFastNonTouchWakeScript",
      "private suspend fun runFastNonTouchScript"
    ).forEachIndexed { index, signature ->
      val next = listOf(
        "private suspend fun runSensitiveFastNonTouchScript",
        "private suspend fun runFastOneShotControlSurfaceCloseInput",
        "private suspend fun runFastRecoveryInput",
        "private suspend fun runFastNonTouchWakeScript",
        "private suspend fun runFastNonTouchScript",
        "private fun boundedNonTouchCommand"
      )[index]
      val helper = body(service, signature, next)
      assertTrue("missing panel-dark gate in $signature", helper.contains(
        "runPanelDarkCommand(reason)"
      ))
    }
    val submitTap = body(
      service,
      "private suspend fun tapControlCodePointWithoutKeyboard",
      "private suspend fun waitForEnteredControlCodeValueVisualProof"
    )
    assertTrue(submitTap.contains("runFastNonTouchInput("))
    assertFalse(submitTap.contains("inputRootExecutor."))
    val launch = body(
      service,
      "private suspend fun launchViviForWake",
      "private fun remainingWakeBudgetMillis"
    )
    assertTrue(launch.contains("runPanelDarkCommand("))
    assertTrue(launch.indexOf("runPanelDarkCommand(") < launch.indexOf("launchVivi()"))
  }

  @Test
  fun controlCodeTerminalReadinessWaitsForKeyboardTailFreshZeroAndExactShutdownInsideLane() {
    val request = body(
      service,
      "private suspend fun handleGenerateControlCode(",
      "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr"
    )
    val laneStart = request.indexOf("controlCodePhoneMutationLane.withOwnership {")
    val keyboardRelease = request.indexOf(
      "keyboardClampReleased = keyboardClampLease?.release(",
      laneStart
    )
    val tail = request.indexOf(
      "panelDarkLease?.releaseAfterFinalConvergence(\"control_code_terminal\")",
      keyboardRelease
    )
    val clearOwner = request.indexOf("activeTicketActionPanelDarkLease = null", tail)
    val clearTail = request.indexOf(
      "clearNonTouchInputTailForBrowserCriticalAction(",
      clearOwner
    )
    val safeGate = request.indexOf("panelFinalization?.safe == true", clearTail)
    val localCleanupCommit = request.indexOf(
      "commitControlCodeCleanStateAfterPanelFinalization(",
      safeGate
    )
    val rawReady = request.indexOf("publishControlCodeReadyAfterPanelFinalization", localCleanupCommit)
    val cleanupSuccess = request.indexOf("sendControlCodeCleanup(", rawReady)
    val fastReady = request.indexOf("markControlCodeFastReady(", cleanupSuccess)
    val laneEnd = request.lastIndexOf("\n      }")
    assertTrue(laneStart >= 0)
    assertTrue(keyboardRelease > laneStart)
    assertTrue(tail > keyboardRelease)
    assertTrue(clearOwner > tail)
    assertTrue(clearTail > clearOwner)
    assertTrue(safeGate > clearTail)
    assertTrue(localCleanupCommit > safeGate)
    assertTrue(rawReady > localCleanupCommit)
    assertTrue(cleanupSuccess > rawReady)
    assertTrue(fastReady > cleanupSuccess)
    assertTrue(laneEnd > fastReady)
    assertTrue(request.contains("cleanupPending = afterPossibleDispatch"))
    assertTrue(request.contains("control_code_cleanup_attention_needed"))
    assertTrue(request.contains("control_code_panel_dark_unavailable"))
    assertTrue(request.contains("\"control_code_request_unsafe_finalization\""))
  }

  @Test
  fun scheduledControlExitCleanupOwnsPanelLeaseAndClearsNothingBeforeSafeFinalization() {
    val schedule = body(
      service,
      "private fun scheduleControlExitCleanup",
      "private suspend fun runScheduledControlExitCleanupWithPanelDarkLease"
    )
    val cleanup = body(
      service,
      "private suspend fun runScheduledControlExitCleanupWithPanelDarkLease",
      "private suspend fun runControlExitCleanup"
    )
    val complete = body(
      service,
      "private suspend fun completeControlExitCleanup",
      "private fun commitControlCodeCleanStateAfterPanelFinalization"
    )
    assertTrue(schedule.contains("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(schedule.contains("runScheduledControlExitCleanupWithPanelDarkLease(reason)"))
    val acquire = cleanup.indexOf("panelLease.acquire()")
    val mutate = cleanup.indexOf("runControlExitCleanup(reason)", acquire)
    val tail = cleanup.indexOf("panelLease.releaseAfterFinalConvergence", mutate)
    val safeGate = cleanup.indexOf(
      "ticketControlCodeCleanupMayCommitAfterPanelFinalization(",
      tail
    )
    val clearCheckpoint = cleanup.indexOf(
      "commitControlCodeCleanStateAfterPanelFinalization(\"scheduled:${'$'}reason\")",
      safeGate
    )
    val rawReady = cleanup.indexOf("publishControlCodeReadyAfterPanelFinalization", clearCheckpoint)
    val failClosed = cleanup.indexOf("preserveControlCodeCleanupCheckpoint", rawReady)
    assertTrue(acquire >= 0)
    assertTrue(mutate > acquire)
    assertTrue(tail > mutate)
    assertTrue(safeGate > tail)
    assertTrue(clearCheckpoint > safeGate)
    assertTrue(rawReady > clearCheckpoint)
    assertTrue(failClosed > rawReady)
    assertTrue(cleanup.contains("activeTicketActionPanelDarkLease = panelLease"))
    assertTrue(cleanup.contains("withContext(NonCancellable)"))
    assertTrue(cleanup.contains("TICKET_SESSION_NEEDS_ATTENTION"))
    assertTrue(complete.contains("val deferReadyPublication = activeTicketActionPanelDarkLease != null"))
    assertFalse(complete.contains("activeControlCodeRawVisualSignature = \"\""))
    assertFalse(complete.contains("activeControlCodeGeneratedVisualSignature = \"\""))
  }

  @Test
  fun v3EveryJournalAndPublicTerminalWaitsForPanelDarkFinalization() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val tail = wrapper.indexOf("panelLease.releaseAfterFinalConvergence")
    val finalProof = wrapper.indexOf(
      "provisional.semanticProof && provisional.actionId == request.actionId",
      tail
    )
    val finalSafeGate = wrapper.indexOf("completedLease?.safe != true", tail)
    val terminalPersist = wrapper.indexOf("persistTicketVisualTerminalSnapshot", finalSafeGate)
    assertTrue(tail >= 0)
    assertTrue(finalProof > tail)
    assertTrue(finalSafeGate > tail)
    assertTrue(terminalPersist > finalSafeGate)
    listOf(
      "provisional.semanticProof",
      "provisional.actionId == request.actionId",
      "generation == ticketActionV3Generation"
    ).forEach { assertTrue(wrapper.contains(it)) }
    val staleProofTerminal = wrapper.substringAfter(
      "provisionalHasBoundVisualProof && !successfulProofCurrent ->"
    )
      .substringBefore("provisional.ok && !recoveredControlCodeCleanupCommitted ->")
    assertTrue(staleProofTerminal.contains("status = \"needs_attention\""))
    assertTrue(staleProofTerminal.contains("currentView = TicketVisualActionView.UNKNOWN"))
    assertTrue(staleProofTerminal.contains("\"ticket_action_frame_watermark_unproved\""))
    assertTrue(staleProofTerminal.contains("\"ticket_action_visual_unproved\""))
    assertTrue(staleProofTerminal.contains("switchAvailable = false"))
    assertTrue(staleProofTerminal.contains("sliderRegion = null"))
    assertTrue(wrapper.contains(
      "(terminal.ok || terminalExpectedNegativeProof) && successfulProofCurrent"
    ))
    assertTrue(wrapper.contains("ticket_action_activation_dispatch_uncertain"))
    assertTrue(wrapper.contains("ticket_action_navigation_dispatch_uncertain"))
    assertTrue(wrapper.contains("ticket_action_failed"))

    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )
    assertTrue(terminal.contains("ticketVisualActionCaptureLeaseActive"))
    assertTrue(terminal.contains("ticketActionV3Snapshot.actionId == request.actionId"))
    val defer = terminal.indexOf("if (deferred) return snapshot")
    assertTrue(defer >= 0)
    assertTrue(terminal.indexOf("persistTicketVisualTerminalSnapshot") > defer)
    assertFalse(terminal.contains("deferProvedMutationTerminal"))
    assertFalse(wrapper.contains("terminal != provisional"))
    assertEquals(1, Regex("persistTicketVisualTerminalSnapshot\\(").findAll(wrapper).count())
    val phase = wrapper.substringAfter("val stablePhase =").substringBefore("rawTerminal.copy(")
    assertTrue(phase.contains("ticketActivationFailureTerminalPhase("))
    assertFalse(phase.contains("mutationMayHaveDispatched"))
    val staging = body(service, "internal fun stageTicketVisualActionFinalization",
      "internal fun pendingTicketVisualActionFinalization")
    assertFalse(staging.contains("persistTicketVisualActionJournal"))
    assertTrue(staging.contains("it.action == action.copy("))
    val writer = body(service, "private fun persistTicketVisualTerminalSnapshot",
      "private fun loadTicketVisualActionJournal")
    assertTrue(writer.contains("if (prior.hasRetainedTerminal) return prior == terminalJournal"))
  }

  @Test
  fun v3RechecksPanelImmediatelyBeforeLaunchAndDefersRecoveredControlReadiness() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val launch = action.indexOf("launchViviForWake(\"ticket_action_v3:")
    val focus = action.indexOf("viviFocusedForFastPublicOpen(\"ticket_action_v3_prelaunch\")")
    val immediateGate = action.lastIndexOf("if (!panelLease.beforeMutationAllowed())", launch)
    assertTrue(launch >= 0)
    assertTrue(focus in 0 until immediateGate)
    assertTrue(immediateGate >= 0)
    assertTrue(launch > immediateGate)
    assertFalse(action.substring(0, launch).contains("panelLease.markMutationMayHaveDispatched()"))
    assertTrue(action.contains("ticket_action_v3_vivi_launch_skipped"))

    val tail = wrapper.indexOf("panelLease.releaseAfterFinalConvergence")
    val proof = wrapper.indexOf("val successfulProofCurrent", tail)
    val cleanupCommit = wrapper.indexOf(
      "commitControlCodeCleanStateAfterPanelFinalization(\"ticket_action_visual_reopen\")",
      proof
    )
    val terminalPersist = wrapper.indexOf("persistTicketVisualTerminalSnapshot", cleanupCommit)
    val rawReady = wrapper.indexOf("publishControlCodeReadyAfterPanelFinalization", terminalPersist)
    assertTrue(proof > tail)
    assertTrue(cleanupCommit > proof)
    assertTrue(terminalPersist > cleanupCommit)
    assertTrue(rawReady > terminalPersist)
    assertTrue(wrapper.contains(
      "preserveControlCodeCleanupCheckpoint(\"ticket_action_visual_reopen_finalization_unproved\")"
    ))
  }

  @Test
  fun physicalTouchAfterRegistrationStartsStopsUnknownWithoutReclassificationOrAnotherGesture() {
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val stroke = activation.indexOf("performTicketSliderFullStroke(")
    val physicalTouchCheck = activation.indexOf(
      "val postGestureLeaseReady = panelLease.beforeMutationAllowed()",
      stroke
    )
    val stoppedBranch = activation.substring(physicalTouchCheck)
      .substringBefore("var postGestureObservation")
    assertTrue(stroke >= 0)
    assertTrue(physicalTouchCheck > stroke)
    assertTrue(stoppedBranch.contains("ticketActivationCheckpointStore.recordNeedsAttention(dispatching)"))
    assertTrue(stoppedBranch.contains("status = \"needs_attention\""))
    assertTrue(stoppedBranch.contains("ticket_action_activation_outcome_unknown"))
    assertTrue(stoppedBranch.contains("terminalPhase = \"outcome_unknown\""))
    assertFalse(stoppedBranch.contains("awaitStableTicketVisualActionObservation("))
    assertFalse(stoppedBranch.contains("recordTicketActivationProven("))
    assertFalse(stoppedBranch.contains("rememberTicketVisualActivatedAnchor("))
    assertFalse(service.contains("reconcileTicketRegistrationAfterUncertainDispatch("))
    val wrapperTouchBranch = wrapper.substringAfter(
      "leaseState.physicalTouchPreempted && leaseState.mutationMayHaveDispatched ->"
    ).substringBefore("leaseState.physicalTouchPreempted ->")
    assertTrue(wrapperTouchBranch.contains("ticket_action_physical_touch_preempted_after_dispatch"))
    assertTrue(wrapperTouchBranch.contains("terminalPhase = \"outcome_unknown\""))
    assertFalse(wrapperTouchBranch.contains("recordTicketActivationProven("))
    assertFalse(wrapperTouchBranch.contains("rememberTicketVisualActivatedAnchor("))
    assertTrue(activation.contains("gestureResult != TicketSliderGestureDispatchResult.COMPLETED"))
    assertTrue(activation.contains("ticket_action_gesture_completion_uncertain"))
    assertFalse(activation.contains("startTicketSliderGesture("))
    assertFalse(activation.contains("continueTicketSliderGesture("))
    assertFalse(activation.contains("endTicketSliderGesture("))
    assertFalse(activation.contains("retryTicketSliderFullStroke("))
    val postProofFence = activation.substringAfter("var postGestureObservation")
      .substringBefore("val resultGenerationCurrent")
    assertTrue(postProofFence.contains("panelLease.beforeMutationAllowed()"))
    assertTrue(postProofFence.contains("ticketActivationPhysicalTouchFenceIsCurrent("))
    assertTrue(postProofFence.contains("ticket_action_activation_outcome_unknown"))
    assertTrue(postProofFence.contains("terminalPhase = \"outcome_unknown\""))
    assertFalse(postProofFence.contains("recordTicketActivationProven("))
    assertFalse(postProofFence.contains("rememberTicketVisualActivatedAnchor("))
  }

  @Test
  fun physicalRevealRefreshesProofWithoutReplayingACompletedStroke() {
    val activation = body(service, "private suspend fun activateTicketFromVisualAction", "private suspend fun prepareExactTicketActivationDispatch")
    val preparation = body(service, "private suspend fun prepareExactTicketActivationDispatch(", "private suspend fun prepareExactTicketActivationDispatchOnce(")
    val postStroke = activation.substringAfter("gestureResult != TicketSliderGestureDispatchResult.COMPLETED")
    val proof = postStroke.substringAfter("var postGestureObservation").substringBefore("val resultGenerationCurrent")
    assertTrue(preparation.contains("repeat(3)"))
    assertTrue(preparation.contains("before.touchBeginCount != after.touchBeginCount"))
    assertTrue(preparation.contains("prepareExactTicketActivationDispatchOnce("))
    assertTrue(proof.contains("for (proofAttempt in 1..3)"))
    assertTrue(proof.indexOf("panelLease.beforeMutationAllowed()") < proof.indexOf("val postLiftFence"))
    assertTrue(proof.contains("postLiftFence,"))
    assertFalse(postStroke.contains("performTicketSliderFullStroke("))
    val retryFence = postStroke.substringAfter("val exactNoTransition").substringBefore("val noTransition")
    assertTrue(retryFence.contains("physicalTouchFence, PhoneAutomationServiceBridge.currentRootPhysicalTouchState()"))
    assertTrue(retryFence.contains("postGestureObservation.currentAnchor == provenDetailAnchor"))
  }

  @Test
  fun readOnlyPhysicalTouchRestartsOnlyBoundedProofWithSameActionGeneration() {
    val wrapper = body(service, "private suspend fun runReadOnlyTicketVisualProofV3(", "private suspend fun runReadOnlyTicketVisualProofV3Once(")
    assertTrue(wrapper.contains("repeat(3)"))
    assertTrue(wrapper.contains("while (PhoneAutomationServiceBridge.currentRootPhysicalTouchState().active)"))
    assertTrue(wrapper.contains("ticketActionV3MutationGeneration != mutationGeneration"))
    assertTrue(wrapper.contains("runReadOnlyTicketVisualProofV3Once(command, request, generation, requireFreshRawDetail)"))
    assertFalse(wrapper.contains("performTicketSliderFullStroke("))
    assertFalse(wrapper.contains("launchViviForWake("))
  }

  @Test
  fun v3RegistrationReadiesAccessibilityBeforeFreshProofAndUsesDistinctPostStrokeReasons() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val preparation = body(
      service,
      "private suspend fun prepareExactTicketActivationDispatch",
      "private suspend fun ticketActivationPreparationStillCurrent"
    )
    val readiness = action.indexOf("PhoneAutomationServiceBridge.awaitAccessibilityConnection(")
    val initialProof = action.indexOf("awaitStableTicketVisualActionObservation(")
    val connectedFence = preparation.indexOf("PhoneAutomationServiceBridge.awaitStableTicketInputFence(")
    val semanticProof = preparation.indexOf("awaitStableTicketSemanticSliderBounds(")
    val freshExactProof = preparation.indexOf("awaitStableTicketVisualActionObservation(")
    val watermark = preparation.indexOf("awaitTicketVisualActionFrameWatermark(")

    assertTrue(readiness >= 0)
    assertTrue(initialProof > readiness)
    assertTrue(connectedFence >= 0)
    assertTrue(semanticProof > connectedFence)
    assertTrue(freshExactProof > semanticProof)
    assertEquals(-1, watermark)
    assertEquals(1, Regex("fastTicketRegistrationHierarchy\\(").findAll(preparation).count())
    assertTrue(preparation.contains("TicketSemanticSliderStabilizationStatus.MISSING"))
    assertTrue(preparation.contains("TicketSemanticSliderStabilizationStatus.UNSTABLE"))
    assertTrue(preparation.contains("TicketSemanticSliderStabilizationStatus.FENCE_CHANGED"))
    assertTrue(preparation.contains("ticketVisualObservationIsFreshForDispatch("))
    assertFalse(preparation.contains("synchronized(encoderLock)"))
    assertTrue(activation.contains("ticketVisualActivationObservationAfterCompletedGesture("))
    assertTrue(activation.contains("ticket_action_gesture_completed_no_transition"))
    assertTrue(activation.contains("ticket_action_post_gesture_visual_unproved"))
    assertTrue(activation.contains("val provenDetailAnchor"))
    assertFalse(activation.contains("provenBottomTab"))
    assertFalse(preparation.contains("bottomTab"))
    assertTrue(activation.contains("ticketActivationCheckpointStore.recordNoTransitionProven(dispatching)"))
    assertTrue(activation.contains("ticket_action_no_transition_checkpoint_unproved"))
  }

  @Test
  fun exactRegistrationFreshnessCannotBeRenewedByALaterGenericWatermark() {
    val preparation = body(
      service,
      "private suspend fun prepareExactTicketActivationDispatch",
      "private suspend fun ticketActivationPreparationStillCurrent"
    )
    val currentness = body(
      service,
      "private suspend fun ticketActivationPreparationStillCurrent",
      "private suspend fun ticketActivationInputFenceStillCurrent"
    )
    val observation = body(
      service,
      "private suspend fun awaitStableTicketVisualActionObservation",
      "private fun TicketVisualActionObservation.toRecoveryState"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )

    assertTrue(observation.contains("copy(atMillis = started)"))
    assertTrue(preparation.contains("ticketVisualObservationIsFreshForDispatch("))
    assertFalse(preparation.contains("awaitTicketVisualActionFrameWatermark("))
    assertTrue(currentness.contains("ticketVisualObservationIsFreshForDispatch("))
    assertTrue(currentness.contains("streamEpoch == prepared.captureStreamEpoch"))
    assertFalse(currentness.contains("watermark"))
    assertFalse(currentness.contains("encoderLock"))
    assertTrue(activation.indexOf("ticketActivationPreparationStillCurrent(") <
      activation.indexOf("recordTicketActivationDispatching("))
    assertTrue(activation.lastIndexOf("ticketActivationPreparationStillCurrent(") <
      activation.indexOf("performTicketSliderFullStroke("))
  }

  @Test
  fun exactRegistrationReusesItsObservedCaptureWithoutRequestingAnExtraFrame() {
    val preparation = body(service, "private suspend fun prepareExactTicketActivationDispatch", "private suspend fun ticketActivationPreparationStillCurrent")
    val broadcast = body(service, "private fun broadcastFrame(", "private fun sendVideoFrame(")
    assertFalse(service.contains("fun awaitTicketVisualActionFrameWatermark"))
    assertFalse(preparation.contains("requestKeyFrame("))
    assertFalse(preparation.contains("encoderLock"))
    assertTrue(broadcast.contains("captureStartUs = sourceFrame.captureStartUs"))
  }

  @Test
  fun everyExactPreparationFailureReturnsBeforeAnyGesture() {
    val preparation = body(
      service,
      "private suspend fun prepareExactTicketActivationDispatch",
      "private suspend fun ticketActivationPreparationStillCurrent"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    listOf(
      "ticket_action_panel_dark_preempted",
      "ticket_action_accessibility_unavailable",
      "ticket_action_input_window_unproved",
      "ticket_action_visual_unproved",
      "ticket_action_detail_identity_conflict",
      "ticket_action_slider_unproved",
      "ticket_action_slider_geometry_invalid",
      "ticket_action_exact_input_fence_changed"
    ).forEach { reason -> assertTrue("missing zero-gesture fence $reason", preparation.contains(reason)) }
    assertFalse(preparation.contains("performTicketSliderFullStroke("))
    val failedFence = activation.indexOf("if (prepared == null)")
    val dispatchCheckpoint = activation.indexOf("recordTicketActivationDispatching(", failedFence)
    val gesture = activation.indexOf("performTicketSliderFullStroke(", dispatchCheckpoint)
    assertTrue(failedFence >= 0)
    assertTrue(dispatchCheckpoint > failedFence)
    assertTrue(gesture > dispatchCheckpoint)
    assertTrue(activation.substring(failedFence, dispatchCheckpoint).contains("return ticketVisualActionTerminal("))
  }

  @Test
  fun ambiguousOrRejectedAndroidDispatchNeverReachesRetryProof() {
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val dispatchResult = activation.indexOf(
      "if (gestureResult != TicketSliderGestureDispatchResult.COMPLETED)"
    )
    val completedProof = activation.indexOf("var postGestureObservation", dispatchResult)
    assertTrue(dispatchResult >= 0)
    assertTrue(completedProof > dispatchResult)
    val rejectedBranch = activation.substring(dispatchResult, completedProof)
    assertTrue(rejectedBranch.contains("ticket_action_gesture_rejected"))
    assertTrue(rejectedBranch.contains("ticket_action_gesture_completion_uncertain"))
    assertTrue(rejectedBranch.contains("terminalPhase = \"outcome_unknown\""))
    assertTrue(rejectedBranch.contains("return ticketVisualActionTerminal("))
    assertFalse(rejectedBranch.contains("recordNoTransitionProven"))
  }

  @Test
  fun eachActivationAttemptResumesViviAndRechecksTheFullFence() {
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val preparation = body(
      service,
      "private suspend fun prepareExactTicketActivationDispatch",
      "private suspend fun ticketActivationPreparationStillCurrent"
    )
    assertTrue(activation.contains("for (ordinal in 1..2)"))
    assertTrue(activation.contains("resumeVivi = true"))
    assertTrue(preparation.contains("launchViviForWake(\"ticket_action_v3_retry:"))
    assertTrue(preparation.contains("awaitStableTicketInputFence("))
    assertTrue(preparation.contains("awaitStableTicketVisualActionObservation("))
    assertTrue(preparation.contains("ticketRegistrationSliderBoundsForHierarchy"))
    assertTrue(preparation.contains("ticketActivationPreparationStillCurrent("))
    assertTrue(phoneAutomationAccessibilityService.contains("ticketInputFenceGenerationsAreCurrent("))
  }

  @Test
  fun oneConclusiveNoTransitionCanPrepareOneSameActionRetry() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val freshObservation = action.indexOf("awaitStableTicketVisualActionObservation(")
    val actionScopedCheckpoint = action.indexOf(
      "ticketActivationCheckpoint(command.id, checkpointRevision, request.attemptId)"
    )
    val freshCheckpoint = activation.indexOf("recordTicketActivationFreshProof(")
    val stroke = activation.indexOf("PhoneAutomationServiceBridge.performTicketSliderFullStroke(")

    assertTrue(freshObservation >= 0)
    assertTrue(actionScopedCheckpoint > freshObservation)
    assertTrue(freshCheckpoint >= 0)
    assertTrue(stroke > freshCheckpoint)
    assertEquals(1, Regex("performTicketSliderFullStroke\\(").findAll(activation).count())
    assertTrue(activation.contains("for (ordinal in 1..2)"))
    assertTrue(activation.contains("recordNoTransitionProven(dispatching)"))
    assertTrue(activation.contains("recordTicketActivationDispatching(requireNotNull(checkpoint), ordinal)"))
    assertFalse(activation.contains("retryTicketSliderFullStroke("))
    assertFalse(spacetimeWorker.contains("ticketremote_retry_ticket_action_v3_after_no_transition"))
  }

  @Test
  fun controlCodeValueProofOutwaitsFocusAnimationWithoutRelaxingSubmitAuthority() {
    val proof = body(
      service,
      "private suspend fun waitForEnteredControlCodeValueVisualProof",
      "private suspend fun waitForGeneratedControlCodeResultAfterSubmit"
    )

    assertTrue(service.contains("CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES = 8"))
    assertTrue(proof.contains("TicketControlCodeSubmitVisualProof()"))
    assertTrue(proof.contains("CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES"))
    assertTrue(proof.contains("ControlCodeEnteredValueProof.STATIC_BLANK"))
    assertFalse(proof.contains("executeRootControlCodeType("))
    assertFalse(proof.contains("tapControlCodePointWithoutKeyboard("))
  }

  @Test
  fun controlCodeCleanupDeliveryUsesTheIndependentResultSender() {
    val publisher = body(spacetimeWorker, "private suspend fun publishNextPhoneResult", "private fun shouldMeasureBrowserCriticalCommand")
    assertTrue(publisher.contains("isControlCodeCleanupHandoffPayload(payload) -> 0"))
    assertTrue(spacetimeWorker.contains("notifications = service.ticketSpacetimeResultNotifications"))
    assertFalse(spacetimeWorker.contains("drainControlCodeCleanupHandoff"))
    assertFalse(spacetimeWorker.contains("CONTROL_CODE_CLEANUP_HANDOFF_DRAIN"))
  }

  @Test
  fun terminalSettlementUsesOneAtomicFinalizerWithoutAChildOrFallback() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun publishNextPhoneResult"
    )
    val finalizerCall = spacetimeWorker.indexOf("client.finalizeTicketActionV3(config, envelope)")
    val localCompletion = spacetimeWorker.indexOf(
      "service.completeTicketVisualActionFinalization(envelope)",
      finalizerCall
    )

    assertTrue(finalizerCall >= 0)
    assertTrue(localCompletion > finalizerCall)
    assertTrue(spacetimeWorker.contains("ticketremote_finalize_ticket_action_v3"))
    assertTrue(spacetimeWorker.contains("envelope.commandRevision"))
    assertFalse(cycle.contains("commandDispatchMutex.withLock"))
    val subscription = body(
      spacetimeWorker,
      "private suspend fun handleSubscribedTicketCommand",
      "private suspend fun handleKeyframeCommand"
    )
    assertTrue(subscription.contains("service.pendingTicketVisualActionFinalization() != null"))
    assertTrue(subscription.indexOf("pendingTicketVisualActionFinalization") <
      subscription.indexOf("subscribedCommandHandoff.fromSubscription"))
    val admission = body(
      service,
      "private suspend fun handleTicketVisualActionV3",
      "private suspend fun runReadOnlyTicketVisualProofV3"
    )
    assertTrue(admission.contains("retainedTerminal.hasRetainedTerminal"))
    assertTrue(admission.contains("ticket_action_v3_terminal_finalization_pending"))
    val localCompletionBody = body(
      service,
      "internal fun completeTicketVisualActionFinalization",
      "private fun clearTicketVisualActionJournal"
    )
    assertTrue(localCompletionBody.contains(
      "if (!journal.hasRetainedTerminal) return journal.actionId.isBlank()"
    ))
    assertTrue(localCompletionBody.contains(
      "ticketActivationCheckpointSafeToClearAfterTerminalFinalization("
    ))
    assertTrue(activationCheckpoint.contains(
      "TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,"
    ))
    assertTrue(activationCheckpoint.contains(
      "TicketActivationCheckpointStage.NEEDS_ATTENTION -> false"
    ))
    assertFalse(spacetimeWorker.contains("ticketremote_retry_ticket_action_v3_after_no_transition"))
    assertFalse(spacetimeWorker.contains("updateTicketActionV3TerminalProjection"))
    val client = body(
      spacetimeWorker,
      "suspend fun finalizeTicketActionV3",
      "private fun sqlLiteral"
    )
    assertFalse(client.contains("compatibility_fallback"))
    assertTrue(client.contains("val action = envelope.action"))
    assertFalse(client.contains("query("))
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
      "private val ticketActionPanelDarkRootExecutor = TicketRootCommandWorker()"
    ))
    assertTrue(service.contains(
      "private val ticketActionPanelDarkVerifyRootExecutor = TicketRootCommandWorker()"
    ))
    assertTrue(startup.contains("ticketActionPanelDarkVerifyRootExecutor.runScript("))
    val destroy = body(service, "override fun onDestroy()", "private fun startServer")
    assertTrue(destroy.contains("ticketActionPanelDarkRootExecutor.close()"))
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
    val sessionBypass = sessionStart.indexOf("val safetyPreflight = runSessionStartSafetyPreflight()")
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
      "private fun boundedStartupTraceCorrelationId"
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
    assertTrue(ensure.contains("val acquire = runWithFallback(ACQUIRE_SCRIPT)"))
    assertTrue(ensure.contains("readbackFromResult(acquire)"))
    assertTrue(ensure.contains("after?.liveActive == true"))
    assertTrue(ensure.contains("after.savedOriginalValid"))
    assertFalse(ensure.contains("if (state.active)"))
    assertTrue(secureCaptureBypassOwner.contains("# ticket_secure_capture_acquire"))
    assertTrue(secureCaptureBypassOwner.contains("acquire_outcome=ownership_established"))
    assertTrue(secureCaptureBypassOwner.contains("Final live proof is part of this same transaction"))
    assertTrue(ensure.contains("preserveExistingLease"))
    assertTrue(ensure.contains("failEnsurePreservingCurrentOwner"))
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
    val start = action.indexOf("val startResponse = startTicketSession(")
    val gate = action.indexOf("if (!startResponse.ok || !streamActive")
    val unavailable = action.indexOf("ticket_action_visual_stream_unavailable")
    val launch = action.indexOf("launchViviForWake", start)
    assertTrue(start >= 0)
    assertTrue(gate > start)
    assertTrue(unavailable > gate)
    assertTrue(launch > unavailable)
    assertFalse(action.substring(start, launch).contains("panelLease.markMutationMayHaveDispatched()"))
  }

  @Test
  fun ticketUsesOnlyBoundedRegistrationSemanticsWhileRsKeepsItsDump() {
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
    assertTrue(service.contains("snapshotTicketRegistrationNodes"))
    val registrationHierarchy = body(
      service,
      "private suspend fun fastTicketRegistrationHierarchy",
      "private fun buildFastVisibleHierarchy"
    )
    assertTrue(registrationHierarchy.contains("snapshotTicketRegistrationNodes"))
    assertFalse(registrationHierarchy.contains("dumpViviHierarchy"))

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
    val handler = body(service, "private suspend fun handleTicketVisualActionV3", "private suspend fun runTicketVisualActionV3")
    assertTrue(handler.contains("ticket_action_v3_internal_failure"))
    assertFalse(handler.contains("safeErrorDetail(error)"))
    assertFalse(handler.contains("error.message"))
  }

  @Test
  fun terminalV3JournalClearsOnlyAfterDurableAck() {
    val terminal = body(service, "private fun ticketVisualActionTerminal", "private fun persistTicketVisualTerminalSnapshot")
    val poll = body(spacetimeWorker, "private suspend fun runCycle", "private fun shouldMeasureBrowserCriticalCommand")
    assertTrue(terminal.contains("persistTicketVisualTerminalSnapshot"))
    assertFalse(terminal.contains("clearTicketVisualActionJournal"))
    val finalize = poll.indexOf("client.finalizeTicketActionV3(config, envelope)")
    val clear = poll.indexOf("service.completeTicketVisualActionFinalization(envelope)", finalize)
    assertTrue(finalize >= 0)
    assertTrue(clear > finalize)
    assertTrue(service.contains("pendingTicketVisualActionFinalization"))
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
    assertTrue(persist.contains("val persisted = ticketVisualActionJournalWriteProved("))
    assertTrue(persist.contains("if (persisted && value.hasRetainedTerminal) requestTicketSpacetimeResultPublication()"))
    assertTrue(persist.contains("readBack = ::loadTicketVisualActionJournal"))
    assertTrue(visualAction.contains("if (!commit()) return false"))
    assertTrue(visualAction.contains("return readBack() == value"))
  }

  @Test
  fun successfulTerminalsUseRawObservationsWithoutWaitingForEncoding() {
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
      "private fun ticketVisualActionSuccess",
      "private fun ticketVisualActionTerminal"
    )
    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )
    assertFalse(action.contains("ticketVisualActionTerminal(request, true"))
    assertTrue(action.contains("ticketVisualActionSuccess("))
    assertFalse(activation.contains("awaitTicketVisualActionFrameWatermark"))
    assertFalse(activation.contains("proofWatermark = watermark"))
    assertTrue(activation.indexOf("val checkpointVisualMatches") <
      activation.indexOf("rememberTicketVisualActivatedAnchor(observation)"))
    assertTrue(activation.contains("updateActivatedAnchor && !checkpointRecovery"))
    assertTrue(activation.contains("activationRevision.takeIf { value.ok }.orEmpty()"))
    assertTrue(activation.contains("terminalPhase = \"outcome_unknown\".takeIf"))
    assertFalse(success.contains("awaitTicketVisualActionFrameWatermark"))
    assertFalse(success.contains("bindTicketRegistrationProofToCurrentWatermark"))
    assertTrue(terminal.contains("observationProved"))
    assertTrue(terminal.contains("ticketVisualObservationIsFreshForDispatch("))
    assertFalse(terminal.contains("encoderLock"))
    assertTrue(terminal.contains("val terminalSwitchAvailable = terminalOk && switchAvailable"))
    assertTrue(terminal.contains("switchAvailable = terminalSwitchAvailable"))
  }

  @Test
  fun terminalReducerSettlesOutcomeWhileCurrentObservationOwnsSliderGeometry() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun publishNextPhoneResult"
    )
    val client = body(
      spacetimeWorker,
      "suspend fun finalizeTicketActionV3",
      "private fun sqlLiteral"
    )
    assertTrue(spacetimeWorker.contains("client.finalizeTicketActionV3(config, envelope)"))
    assertFalse(cycle.contains("client.finalizeTicketActionV3(config, envelope)"))
    assertTrue(client.contains("ticketremote_finalize_ticket_action_v3"))
    assertTrue(client.contains("region != null"))
    assertTrue(client.contains("envelope.commandRevision"))
    assertTrue(client.contains("activationRefresh"))
    assertFalse(client.contains("updateTicketActionV3(config, action)"))
    assertFalse(cycle.contains("compatibility_fallback"))
    val subscription = body(
      spacetimeWorker,
      "private suspend fun handleSubscribedTicketCommand",
      "private suspend fun handleKeyframeCommand"
    )
    assertTrue(subscription.contains("pixel_direct_subscription_command_observed"))
    assertTrue(subscription.contains("databaseToPhoneMillis"))
    assertTrue(subscription.contains("priority_subscription"))

    val success = body(
      service,
      "private fun ticketVisualActionSuccess",
      "private fun ticketVisualActionTerminal"
    )
    val persist = body(
      service,
      "private fun persistTicketVisualTerminalSnapshot",
      "private fun loadTicketVisualActionJournal"
    )
    assertFalse(success.contains("sliderRegion ="))
    assertTrue(persist.contains("semanticProof = snapshot.semanticProof"))
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
      "private fun TicketVisualActionObservation.toRecoveryState"
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
    val openControlCode = body(
      service,
      "private suspend fun openControlCodePopupFastForRequest",
      "private fun scheduleControlCodeVisualSignatureExpiry"
    )
    val inline = body(
      service,
      "private suspend fun prepareTicketDetailForControlCodeRequest",
      "private fun rootCaptureNeedsOwnedPreparation"
    )
    assertTrue(openControlCode.contains("TicketVisualPhoneState.ACTIVATED_DETAIL"))
    assertTrue(openControlCode.contains("TicketVisualPhoneState.UNACTIVATED_DETAIL"))
    assertTrue(openControlCode.contains("visualDetail?.controlCodeBounds"))
    assertFalse(openControlCode.contains("TicketVisualPhoneState.TICKET_LIST"))
    assertFalse(openControlCode.contains("backBounds"))
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
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val dispatchWrite = activation.indexOf(
      "val dispatching = recordTicketActivationDispatching(requireNotNull(checkpoint), ordinal)"
    )
    val dispatchFailure = activation.indexOf("ticket_action_activation_dispatch_checkpoint_unproved")
    val mutationBoundary = activation.indexOf("panelLease.markMutationMayHaveDispatched()")
    val heldGesture = activation.indexOf("PhoneAutomationServiceBridge.performTicketSliderFullStroke")

    assertTrue(dispatchWrite >= 0)
    assertTrue(dispatchFailure > dispatchWrite)
    assertTrue(mutationBoundary > dispatchFailure)
    assertTrue(heldGesture > mutationBoundary)
    assertTrue(activationCheckpoint.contains("fun save(checkpoint: TicketActivationCheckpoint): Boolean"))
    assertTrue(activationCheckpoint.contains("if (!backend.save(checkpoint)) return null"))
    assertTrue(activationCheckpoint.contains("if (backend.load() != null) return null"))
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
  fun redetectionPublishesNoTicketOnlyAfterTheOwnedTimeTabProofAndSafeTail() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun activateTicketFromVisualAction"
    )
    val expectedNegative = body(
      service,
      "private suspend fun ticketVisualActionLatestNotDetectedAfterTimeTab",
      "private fun ticketVisualActionSuccess"
    )
    val wrapper = body(
      service,
      "private suspend fun runTicketVisualActionV3(",
      "private suspend fun runTicketVisualActionV3WithCaptureLease"
    )
    val terminal = body(
      service,
      "private fun ticketVisualActionTerminal",
      "private fun persistTicketVisualTerminalSnapshot"
    )

    assertTrue(action.contains(
      "TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->\n" +
        "          observation.timeTicketsNavigationBoundsFor(request.target)"
    ))
    assertTrue(action.contains(
      "TicketVisualPhoneState.TICKETS_TIME_EMPTY ->\n" +
        "          observation.singleUseTicketsNavigationBoundsFor(request.target)"
    ))
    assertEquals(
      2,
      Regex("ticketVisualActionLatestNotDetectedAfterTimeTab\\(").findAll(action).count()
    )
    assertFalse(expectedNegative.contains("awaitTicketVisualActionFrameWatermark("))
    assertTrue(expectedNegative.contains("ticketVisualRedetectLatestNotDetectedObservation("))
    assertTrue(expectedNegative.contains("status = \"failed\""))
    assertTrue(expectedNegative.contains("reason = \"ticket_action_latest_not_detected\""))
    assertTrue(expectedNegative.contains("expectedNegativeProof = true"))
    assertTrue(terminal.contains("observation?.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY"))
    assertTrue(terminal.contains("if (deferred) return snapshot"))

    val release = wrapper.indexOf("panelLease.releaseAfterFinalConvergence")
    val proofRecheck = wrapper.indexOf(
      "provisional.semanticProof && provisional.actionId == request.actionId",
      release
    )
    val terminalPersist = wrapper.indexOf("persistTicketVisualTerminalSnapshot", proofRecheck)
    assertTrue(release >= 0 && proofRecheck > release && terminalPersist > proofRecheck)
    assertTrue(wrapper.contains("provisionalExpectedNegativeProof"))
    assertTrue(wrapper.contains("terminalExpectedNegativeProof"))
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
    assertTrue(visualAction.contains("val refreshActivationAttemptId: String"))
    assertTrue(visualAction.contains("val refreshActivationRevision: String"))
    assertTrue(spacetimeWorker.contains("activationRefresh -> envelope.refreshActivationAttemptId"))
    assertTrue(spacetimeWorker.contains("activationRefresh -> envelope.refreshActivationRevision"))
    assertTrue(spacetimeWorker.contains("ticketremote_finalize_ticket_action_v3"))
  }

  @Test
  fun subscribedTerminalV3NotifiesOneIndependentAtomicFinalizer() {
    val subscribed = body(spacetimeWorker, "private suspend fun handleSubscribedTicketCommand", "private suspend fun handleKeyframeCommand")
    assertTrue(subscribed.contains("service.requestTicketSpacetimeResultPublication()"))
    assertTrue(subscribed.contains("subscribedCommandHandoff.fromSubscription(command.id)"))
    assertFalse(subscribed.contains("client.updateTicketActionV3"))
    assertFalse(subscribed.contains("client.ack("))
    val publisher = body(spacetimeWorker, "private suspend fun publishNextPhoneResult", "private fun shouldMeasureBrowserCriticalCommand")
    val finalizer = publisher.indexOf("client.finalizeTicketActionV3(config, envelope)")
    val complete = publisher.indexOf("service.completeTicketVisualActionFinalization(envelope)")
    assertTrue(finalizer >= 0 && complete > finalizer)
    assertFalse(publisher.contains("commandDispatchMutex.withLock"))
    assertTrue(publisher.contains("subscribedCommandResults.settle(envelope.commandId"))
    assertTrue(publisher.contains("client.updateTicketActionV3(config, progress)"))
  }

  @Test
  fun generatedPhoneStatePrecedesTheSeparateBrowserPictureDelivery() {
    val confirmation = body(service, "private suspend fun confirmGeneratedControlCodeResultForBrowser", "private suspend fun markerFirstControlCodeFrameWatermarkForBrowser")
    val observed = confirmation.indexOf("sendControlCodeProgress(lastControlCodeRequestId.orEmpty(), \"generated\"")
    val picture = confirmation.indexOf("val watermark = markerFirstControlCodeFrameWatermarkForBrowser")
    assertTrue(observed >= 0 && picture > observed)
    assertTrue(confirmation.contains("generated_waiting_for_picture"))
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
  fun resultPublisherReselectsPriorityAfterEveryDeliveryWithoutMediaPolling() {
    val publication = body(spacetimeWorker, "private suspend fun publishNextPhoneResult", "private fun shouldMeasureBrowserCriticalCommand")
    assertTrue(publication.contains("peekTicketSpacetimePhoneMessages(PHONE_MESSAGE_PRIORITY_PEEK_LIMIT)"))
    assertTrue(publication.contains(".minByOrNull"))
    assertTrue(publication.contains("isControlCodeResultPayload(payload) -> 0"))
    assertTrue(publication.contains("service.acknowledgeTicketSpacetimePhoneMessage(message)"))
    assertFalse(spacetimeWorker.contains("drainPhoneMessagesUntilControlCodeResult"))
    assertFalse(spacetimeWorker.contains("routinePhoneMessageDrainLimit"))
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
    assertTrue(clientPost.contains("CharArray(MAX_TICKET_SPACETIME_ERROR_RESPONSE_CHARS)"))
    assertEquals(1, Regex("readText\\(\\)").findAll(clientPost).count())
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
      "internal suspend fun handleTicketSpacetimeDesiredActive"
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
    val bind = socket.indexOf("bindStartupTraceCorrelationIdFromVideoSocket(")
    val opened = socket.indexOf("recordTicketEventForCurrentVideoSocket(")
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
  fun videoFanoutUsesOneInFlightAndOneNewestPendingIndependentFramePerClient() {
    val delivery = source("ticket/TicketVideoClientDeliveryState.kt")
    val writerPump = source("ticket/TicketVideoClientWriterPump.kt")
    val registry = source("ticket/TicketVideoClientDeliveryRegistry.kt")
    val sendFrame = body(service, "private fun sendVideoFrame", "private fun handleRootHardwareH264CaptureFrame")
    val writer = body(service, "private fun launchVideoFrameWriter", "private fun applyVideoDeliveryEffects")
    val effects = body(service, "private fun applyVideoDeliveryEffects", "private fun handleRootHardwareH264CaptureFrame")
    val cached = body(service, "private fun sendCachedKeyFrameOrRequest", "private fun broadcastFrame")

    assertTrue(delivery.contains("private var pendingFrame: TicketVideoDeliveryFrame? = null"))
    assertTrue(delivery.contains("if (!frame.keyFrame)"))
    assertTrue(delivery.contains("frame.sequence <= latestAcceptedSequence"))
    assertTrue(delivery.contains("pendingFrame = frame"))
    assertTrue(delivery.contains("DROP_PENDING_REPLACED"))
    assertTrue(delivery.contains("requestImmediateRefresh = true"))
    assertFalse(delivery.contains("ArrayDeque"))
    assertFalse(delivery.contains("frame.sequence != lastAdmittedSequence + 1L"))
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
    assertTrue(effects.contains("video_client_frame_drop"))
    assertTrue(effects.contains("video_client_unexpected_delta"))
    assertTrue(cached.contains("cached.sequence == sourceTail.second"))
    assertTrue(cached.contains("cached.epoch == sourceTail.first"))
    assertTrue(service.contains("TicketH264FrameRecord.MAX_PAYLOAD_BYTES"))
    assertFalse(service.contains("VIDEO_CLIENT_PENDING_MAX_FRAMES"))
    assertFalse(service.contains("VIDEO_CLIENT_PENDING_MAX_AGE"))
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
    val initialDeltaGuard = receive.indexOf("if (!frame.keyFrame)")
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
    assertTrue(envelope.contains("TicketTsf3FrameEnvelope.encode("))
    assertTrue(tsf3Envelope.contains("buffer.putLong(epoch)"))
    assertTrue(tsf3Envelope.contains("buffer.putLong(sequence)"))
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
    assertTrue(allowlist.contains("TicketTracePrivacy.eventName(event) != null"))
    assertEquals("spacetime_test", TicketTracePrivacy.eventName("spacetime_test"))
    assertEquals("stream_test", TicketTracePrivacy.eventName("stream_test"))
    assertEquals("ticket_brightness_test", TicketTracePrivacy.eventName("ticket_brightness_test"))
    assertEquals("ticket_state_event", TicketTracePrivacy.eventName("ticket_state_event"))
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
      "\"ticket_action_v3\" ->", "\"generate_control_code\" ->",
      "\"control_code_browser_capture\" ->", "\"control_exit\" ->"
    ).forEach { assertTrue("missing retained Spacetime command $it", commands.contains(it)) }
    assertFalse(commands.contains("\"stream_cadence\" ->"))
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

    assertTrue(socket.contains("val clientLifecycleLock = Any()"))
    assertTrue(socket.contains("val videoClientRegistered = AtomicBoolean(false)"))
    assertTrue(socket.contains("val videoReadStarted = if (video) CompletableDeferred<Unit>() else null"))
    assertTrue(socket.contains("videoReadStarted?.complete(Unit)\n        client.readLoop()"))
    assertTrue(socket.contains("videoReadStarted?.await()"))
    assertTrue(socket.indexOf("videoReadStarted?.await()") < socket.indexOf("var acceptedClientGeneration"))
    assertTrue(socket.contains("if (!client.isOpen())"))
    assertTrue(socket.contains("videoClientRegistered.set(true)"))
    assertTrue(socket.contains("mediaCommandsAllowed = videoClientRegistered.get()"))
    assertTrue(socket.contains("if (video && !startTicketSessionForVideoClientOpen(info)) {\n      client.close()\n      videoReadJob?.await()\n      return"))
    assertTrue(socket.contains("val currentAfterStart = sessionMutex.withLock"))
    assertTrue(socket.contains("synchronized(clientLifecycleLock)"))
    assertTrue(socket.contains("!videoClientRegistered.get() ||"))
    assertTrue(socket.contains("!client.isOpen() ||"))
    assertTrue(socket.contains("!videoClients.contains(client) ||"))
    assertTrue(socket.contains("!startupTraceCorrelation.isCurrentVideoSocket("))
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
    assertTrue(socket.indexOf("videoReadStarted?.await()") < socket.indexOf("startTicketSessionForVideoClientOpen(info)"))
    assertTrue(socket.contains("videoReadJob?.await()\n      return"))
    assertTrue(durableStart.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertTrue(durableStart.contains("startTicketSession().toTicketSpacetimeCommandResult(reason)"))
  }

  @Test
  fun expiredRelayCadenceCompatibilityIsRemovedAfterTheDrain() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    assertFalse(commands.contains("\"stream_cadence\" ->"))
    assertFalse(h264Engine.contains("requestCadence"))
    assertFalse(h264Main.contains("cmd.startsWith(\"cadence:\")"))
    assertFalse(h264Main.contains("isLegacyCommandFps"))
  }

  @Test
  fun durableKeyframeAlwaysReachesTheCoalescedEncoderRequestPath() {
    val commands = body(service, "internal suspend fun handleTicketSpacetimeCommand", "internal suspend fun handleTicketSpacetimeDesiredActive")
    val keyframe = commands.substringAfter("\"keyframe\" ->").substringBefore("\"recover_stream\" ->")

    assertTrue(keyframe.contains("requestKeyFrame(reason.ifBlank { \"spacetime_keyframe\" })"))
    assertTrue(keyframe.contains("reason = \"keyframe_requested\""))
    assertFalse(keyframe.contains("ticketSpacetimeBackgroundStreamAlreadyHealthy()"))
    assertFalse(keyframe.contains("stream_already_healthy"))
    assertTrue(h264Engine.contains("pendingKeyFrameRequest.offer(reason)"))
    assertTrue(h264Main.contains("requestNextSyncFrame(syncFrameRequested, frameWaitLock)"))
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
  fun requestLaneAcquiresKeyboardClampBeforePreflightAndReleasesItInFinally() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(generate.contains("RequestScopedKeyboardClampLease"))
    assertTrue(generate.contains("keyboardClampLease?.acquire() == true"))
    assertTrue(service.contains("awaitControlCodeKeyboardClamp"))
    assertTrue(service.contains("keyboard_clamp_requested"))
    assertTrue(service.contains("keyboard_clamp_applied"))
    assertTrue(service.contains("keyboard_clamp_released"))
    assertTrue(generate.contains("withContext(NonCancellable)"))
    assertTrue(generate.indexOf("keyboardClampLease?.acquire() == true") < generate.indexOf("ensureControlCodeRequestPreflight("))
    assertTrue(generate.contains("reason = \"control_code_keyboard_clamp_unavailable\""))
    assertTrue(generate.contains("keyboardClampReleased = keyboardClampLease?.release("))
    assertTrue(generate.contains("panelFinalization?.safe == true && keyboardClampReleased"))
    assertTrue(generate.contains("control_code_keyboard_restore_failed"))
    assertTrue(generate.contains("control_code_keyboard_cleanup_pending"))
    assertTrue(service.contains("suppressionMayBeOwned = true"))
    assertTrue(service.contains("if (acquired || suppressionMayBeOwned)"))
    assertFalse(generate.contains("startAsync()"))
    assertFalse(service.contains("acquireJob"))
    assertTrue(service.contains("PhoneAutomationServiceBridge.suppressViviControlCodeKeyboardMode"))
    assertTrue(service.contains("PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed"))
    assertTrue(service.contains("PhoneAutomationServiceBridge.restoreViviControlCodeKeyboardMode"))
    assertFalse(service.contains("TicketControlCodeKeyboardClamp"))
    assertTrue(service.contains("keyboard_clamp_restore_failed"))
  }

  @Test
  fun serviceDestructionRetainsDurableCleanupWhenKeyboardRestoreIsUnproved() {
    val destroy = body(service, "override fun onDestroy()", "private fun startServer")
    assertTrue(destroy.contains("clamp.release(\"service_destroyed\")"))
    assertTrue(destroy.contains("if (keyboardClampReleased)"))
    assertTrue(destroy.contains("service_destroy_keyboard_restore_unproved"))
    assertTrue(destroy.contains("durable_cleanup_checkpoint_retained=true"))
  }

  @Test
  fun sensitiveControlCodeEntryUsesSharedCancellationAndRedactsAllCommandOutput() {
    val sensitive = body(
      service,
      "private suspend fun runSensitiveFastNonTouchScript",
      "private suspend fun runFastOneShotControlSurfaceCloseInput"
    )
    assertTrue(sensitive.contains("runPanelDarkCommand(reason)"))
    assertTrue(sensitive.contains("boundedNonTouchCommand(command, timeout), timeout"))
    assertTrue(sensitive.contains("copy(command = \"[REDACTED]\", stdout = \"\", stderr = \"\")"))
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
  fun streamRunsOneFpsAllIntraDuringIdleAndControlDispatch() {
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_FPS = 1"))
    assertTrue(config.contains("const val ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE = \"all_intra\""))
    assertTrue(h264Engine.contains("--fps ${'$'}{TicketScreenConfig.ROOT_HARDWARE_H264_FPS}"))
    assertFalse(h264Engine.contains("targetFps"))
    assertTrue(service.contains("val keyframeIntervalFrames = 1"))
  }

  @Test
  fun oneShotRefreshBeginsAtBrowserDispatchBeforePhoneOwnershipWait() {
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val refresh = "requestImmediateRefresh(\"control_code_browser_dispatch\")"
    assertTrue(generate.contains(refresh))
    assertTrue(generate.indexOf(refresh) < generate.indexOf("controlCodePhoneMutationLane.withOwnership"))
    assertTrue(generate.contains("capture_refresh_requested"))
  }

  @Test
  fun browserAckWaitDoesNotOwnAContinuousCaptureBurst() {
    val wait = body(service, "private suspend fun waitForControlCodeBrowserCapture", "private suspend fun ensureTicketSessionForControlCodeRequest")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    assertTrue(wait.contains("control_code_browser_capture_ack_timeout"))
    assertFalse(wait.contains("stopControlCodeRequestBurst"))
    assertFalse(generate.contains("startControlCodeRequestBurst"))
    assertFalse(generate.contains("stopControlCodeRequestBurst"))
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
    val finalVisualProbe = "awaitStableGeneratedControlCodeCloseProbe()"
    val watermarkCall = "requestFreshControlCodeFrameWatermark(reason)"
    assertTrue(service.contains("CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS = 200L"))
    assertTrue(marker.contains(delayEvent))
    assertTrue(marker.contains("measureInputPhase(phases, \"result_marker_delay\")"))
    assertTrue(marker.indexOf(delayEvent) < marker.indexOf(finalVisualProbe))
    assertTrue(marker.indexOf(finalVisualProbe) < marker.indexOf(watermarkCall))
    assertTrue(marker.contains("TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE"))
    assertFalse(marker.contains("same_detail"))
    assertFalse(marker.contains("expectedVisualSignature"))
  }

  @Test
  fun restartedRootEncoderDropsFramesFromThePreviousProcess() {
    val stop = body(
      h264Engine,
      "private fun stopProcesses(generation: Long)",
      "private fun destroyProcessAndWait"
    )
    assertTrue(h264Engine.contains("private val captureGeneration = AtomicLong(0L)"))
    assertTrue(h264Engine.contains("val recordGeneration = advanceCaptureGeneration()"))
    assertTrue(h264Engine.contains("if (sourceGeneration != captureGeneration.get()) continue"))
    assertTrue(stop.contains("advanceCaptureGeneration()"))
  }

  @Test
  fun rootEncoderKeepsHardwareCaptureAliveUntilInputIsDrained() {
    val loop = body(h264Main, "while ((packet = pendingFrames.take()) != null)", "encoder.signalEndOfInputStream()")
    val draw = loop.indexOf("drawBitmap(inputSurface")
    val drain = loop.indexOf("TicketEncoderDrainProgress drainProgress")
    val close = loop.indexOf("packet.close()")
    assertTrue(draw >= 0)
    assertTrue(drain > draw)
    assertTrue(close > drain)
    assertTrue(loop.contains("GPU input retains the bitmap through drain"))
  }

  @Test
  fun ordinaryCaptureOwnsObservationAndNoPrivateStartupCaptureRemains() {
    val loop = body(h264Main, "while (frames <= 0 || sent < frames)", "private static final class PendingCapture")
    assertEquals(1, Regex("capture.capture\\(\\)").findAll(loop).count())
    assertTrue(loop.indexOf("pendingFrames.offer(") < loop.indexOf("classifyControlCodeVisualState("))
    assertTrue(loop.contains("!controlCodeVisualProbeActive || visualProbeRequest.ticketAction"))
    assertTrue(loop.contains("source.retain()"))
    assertFalse(loop.contains("drainEncoder"))
    assertFalse(loop.contains("drawBitmap(inputSurface"))
    assertFalse(h264Main.contains("privatePreparationCapture"))
    assertFalse(h264Main.contains("ticket_preparation_"))
    assertFalse(h264Engine.contains("PreparationWindow"))
    val action = body(service, "private suspend fun runTicketVisualActionV3(", "private suspend fun runTicketVisualActionV3WithCaptureLease(")
    assertFalse(service.contains("beginPreparationWindow"))
    assertTrue(action.contains("request.target != TicketVisualActionTarget.PROVE_CURRENT"))
    assertTrue(action.contains("warmObservation?.cancelAndJoin()"))
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
    assertTrue(open.contains("runFastNonTouchInput("))
    assertTrue(type.contains("TicketControlCodeRootInput.buildTypeScript("))
    assertFalse(type.contains("openX = transaction.open?.x"))
    assertFalse(type.contains("openY = transaction.open?.y"))
  }

  @Test
  fun requestLeaseSuppressesSoftKeyboardWithoutMutatingImeSettingsOrDevices() {
    assertFalse(rootInput.contains("settings "))
    assertFalse(rootInput.contains("ime disable"))
    assertFalse(rootInput.contains("input text"))
    assertFalse(rootInput.contains("input tap"))
    assertTrue(phoneAutomationBridge.contains("suppressViviControlCodeKeyboardMode"))
    assertTrue(phoneAutomationBridge.contains("isViviControlCodeKeyboardModeSuppressed"))
    assertTrue(phoneAutomationAccessibilityService.contains("controller.setShowMode(SHOW_MODE_HIDDEN)"))
    assertTrue(phoneAutomationAccessibilityService.contains("controller.showMode == SHOW_MODE_HIDDEN"))
    assertTrue(phoneAutomationAccessibilityService.contains("VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY"))
    assertFalse(phoneAutomationAccessibilityService.contains("settings put secure show_ime_with_hard_keyboard"))
    assertFalse(phoneAutomationAccessibilityService.contains("ime disable"))
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
    assertTrue(enter.contains("val submitProbe = awaitStableControlCodeSubmitLayout()"))
    assertTrue(enter.contains("it.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY"))
    assertTrue(enter.contains("control_code_submit_target_unproved"))
    assertTrue(enter.contains("submitTransaction.submit.x"))
    assertTrue(enter.contains(submit))
    assertTrue(enter.indexOf(proof) < enter.indexOf(submit))
  }

  @Test
  fun submitIsAnExplicitRootTapAndNotAKeyboardEnterKey() {
    val tap = body(service, "private suspend fun tapControlCodePointWithoutKeyboard", "private suspend fun waitForEnteredControlCodeValueVisualProof")
    assertTrue(tap.contains("input tap ${'$'}x ${'$'}y"))
    assertTrue(tap.contains("runFastNonTouchInput("))
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
    assertFalse(wait.contains("preSubmitDetailAnchor"))
    assertFalse(wait.contains("preSubmitDetailState"))
    assertFalse(wait.contains("awaitStableTicketVisualActionObservation("))
    assertFalse(wait.contains("ticketControlCodeReturnedToSameDetail("))
    assertFalse(wait.contains("same_detail"))
    assertTrue(wait.contains("GENERATED_WITH_CLOSE"))
    assertTrue(wait.contains("generatedWithCloseProof.observe(visualProbe)"))
    assertTrue(wait.contains("requestControlCodeCleanupVisualProbe("))
    assertFalse(wait.contains("requestControlCodeRequestVisualProbe("))
    assertFalse(wait.contains("TicketControlCodeVisualSignatureProof"))
    assertFalse(wait.contains("changedVisualProof"))
    assertTrue(wait.contains("resultProof = \"phone_visual_generated_with_close\""))
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
    assertTrue(begin.contains("awaitStableGeneratedControlCodeCloseProbe()"))
    assertFalse(begin.contains("ticketControlCodeReturnedToSameDetail("))
    assertFalse(begin.contains("control_code_same_detail_noop"))
    assertTrue(begin.contains("TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE"))
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
    assertTrue(oneShot.contains("controlSurfaceCloseRootExecutor.run("))
    assertTrue(oneShot.contains("runPanelDarkCommand(reason)"))
    assertFalse(oneShot.contains("wrapNonTouchPanelSleepClamp("))
    assertFalse(oneShot.contains("runScript("))
    assertTrue(oneShot.contains("CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS.milliseconds"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS = 2_000L"))
    assertFalse(send.contains("runFastInlineControlResultCloseInput"))
    assertFalse(service.contains("private suspend fun runFastInlineControlResultCloseInput"))
  }

  @Test
  fun generatedControlResultRequiresTwoFreshBadgeProbesAndNeverSameDetailFallback() {
    val stable = body(
      service,
      "private suspend fun awaitStableGeneratedControlCodeCloseProbe",
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
    assertTrue(stable.contains("TicketGeneratedWithCloseProof()"))
    assertTrue(stable.contains("proof.observe(current)"))
    assertTrue(stable.contains("CONTROL_CODE_GENERATED_CLOSE_PROOF_TIMEOUT_MILLIS"))
    assertTrue(stable.contains("CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS"))
    assertTrue(stable.contains("minOf(CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS, remainingMillis)"))
    assertFalse(stable.contains("CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS"))
    assertTrue(service.contains("CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS = 1_250L"))
    assertTrue(service.contains("CONTROL_CODE_GENERATED_CLOSE_PROOF_TIMEOUT_MILLIS = 3_200L"))
    assertFalse(stable.contains("visualSignature =="))
    assertTrue(clean.contains("val proofResult = visualProbe.result"))
    assertFalse(clean.contains("baselineSignatureMatches"))
    assertFalse(clean.contains("signatureStillGenerated"))
    assertFalse(clean.contains("activeControlCodeRawVisualSignature"))
    assertTrue(clean.contains("CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS"))
    assertTrue(clean.contains("minOf(CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS, remainingMillis)"))
    assertFalse(clean.contains("minOf(CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS, remainingMillis)"))
    assertTrue(service.contains("CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS = 1_250L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 3_200L"))
    assertTrue(clean.contains("TicketControlCodeCleanupVisualProof(CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT)"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2"))
    assertFalse(finish.contains("control_code_same_detail_noop"))
    assertFalse(finish.contains("same_detail_h264_verified"))
    assertTrue(finish.contains("ticketControlCodeRestoredOriginalDetailMismatch("))
    assertTrue(finish.contains("activeControlCodeRawDetailState"))
    assertTrue(finish.contains("dimension=\$restoredDetailMismatch"))
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
      "private suspend fun publishNextPhoneResult",
      "private fun shouldMeasureBrowserCriticalCommand"
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
    assertTrue(drain.contains("if (outcome.acknowledgePhoneMessage)"))
    assertTrue(drain.indexOf("if (outcome.acknowledgePhoneMessage)") <
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
  fun staticBlankProofSettlesAndReprovesBeforeOneInputManagerRetype() {
    val enter = body(service, "private suspend fun enterAndSubmitControlCodeDigitsFastForRequest", "private suspend fun executeRootControlCodeType")
    val firstProof = enter.indexOf("var valueProof = waitForEnteredControlCodeValueVisualProof(phases)")
    val settle = enter.indexOf("delay(CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS)")
    val secondProof = enter.indexOf("valueProof = waitForEnteredControlCodeValueVisualProof(phases)", settle)
    val retype = enter.indexOf("\"control_code_root_input_manager_retype\"")
    assertTrue(firstProof >= 0)
    assertTrue(firstProof < settle)
    assertTrue(settle < secondProof)
    assertTrue(secondProof < retype)
    assertFalse(enter.contains("remainingInitialKeyboardLeaseMillis"))
    assertFalse(enter.contains("CONTROL_CODE_ROOT_RETYPE_LEASE_MARGIN_MILLIS"))
    assertTrue(service.contains("CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS = 350L"))
  }

  @Test
  fun cleanupRefreshesTheGeneratedResultBoundsBeforeClosing() {
    val begin = body(service, "private suspend fun beginGeneratedControlCodeResultFastClose", "private suspend fun finishGeneratedControlCodeResultFastCleanup")
    val request = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val returnRaw = body(service, "private suspend fun returnControlCodeSurfaceToRawTicket", "private suspend fun beginGeneratedControlCodeResultFastClose")
    assertFalse(request.contains("reuseGeneratedProof"))
    assertFalse(returnRaw.contains("reuseGeneratedProof"))
    assertFalse(begin.contains("reuseGeneratedProof"))
    assertTrue(begin.contains("awaitStableGeneratedControlCodeCloseProbe()"))
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
  fun fastCleanupProofBudgetStaysBoundedWhileAllowingMultiFrameProbeReplies() {
    assertTrue(service.contains("CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS = 1_250L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 3_200L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS = 200L"))
    assertTrue(service.contains("CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2"))
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
    assertFalse(clean.contains("ticketControlCodeExpectedDetailSignatureMatches("))
    assertFalse(clean.contains("activeControlCodeRawVisualSignature"))
    assertTrue(clean.contains("val proofResult = visualProbe.result"))
    assertTrue(clean.contains("static detail anchor"))
  }

  @Test
  fun visualSignatureAnchorsStayPrivateAndCleanupPendingSurvivesAProcessRestart() {
    val create = body(service, "override fun onCreate()", "override fun onStartCommand")
    val generate = body(service, "private suspend fun handleGenerateControlCode", "private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr")
    val requestFinally = generate.substringAfter("} finally {").substringBefore("releaseControlCodeAutomationForRequest")
    val complete = body(service, "private suspend fun completeControlExitCleanup", "private fun recordControlExitCleanup")
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
  fun controlCodeUsesTheAlreadyOpenActivatedOrUnactivatedDetailDirectly() {
    val open = body(
      service,
      "private suspend fun openControlCodePopupFastForRequest",
      "private fun scheduleControlCodeVisualSignatureExpiry"
    )
    assertTrue(open.contains("TicketVisualPhoneState.ACTIVATED_DETAIL"))
    assertTrue(open.contains("TicketVisualPhoneState.UNACTIVATED_DETAIL"))
    assertTrue(open.contains("visualDetail?.controlCodeBounds"))
    assertFalse(open.contains("canonicalizeControlCodeTicketDetail("))
    assertFalse(open.contains("TicketVisualPhoneState.TICKET_LIST"))
    assertFalse(open.contains("backBounds"))
    assertFalse(open.contains("control_code_baseline_visual_unproved"))
    assertFalse(service.contains("awaitStableControlCodeRawVisualSignature"))
    val cleanupCheckpoint = open.indexOf("persistControlCodeSignatureCleanupRequired(true)")
    val popupTap = open.indexOf("runFastNonTouchInput(")
    assertTrue(cleanupCheckpoint >= 0)
    assertTrue(popupTap > cleanupCheckpoint)
    assertFalse(service.contains("private suspend fun canonicalizeControlCodeTicketDetail"))
    assertFalse(service.contains("private suspend fun restoreControlCodeUnactivatedDetailFromList"))
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
  fun cleanupUsesRawDetailAndFinalPanelProofWithoutAnEncodedFrameWait() {
    val complete = body(service, "private suspend fun completeControlExitCleanup", "private fun recordControlExitCleanup")
    assertFalse(service.contains("waitForFreshStreamFrameAfterCleanup"))
    assertFalse(complete.contains("restartActiveStreamEngine"))
    assertTrue(complete.contains("activeTicketActionPanelDarkLease != null"))
    assertTrue(complete.contains("persistControlCodeSignatureCleanupRequired(false)"))
    assertTrue(complete.contains("cleanupRevision = phoneControlState.updates.value.contextRevision"))
    assertTrue(complete.contains("eventFrameSequence = 0L"))
    val publish = body(spacetimeWorker, "private suspend fun publishTicketStateEvent", "private suspend fun publishControlCodeResult")
    assertTrue(publish.contains("require(revision.startsWith(\"pc-\"))"))
    assertTrue(publish.contains("client.completeControlCodeCleanupReady("))
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
    val finalZeroAndStop = generate.indexOf(
      "panelDarkLease?.releaseAfterFinalConvergence(\"control_code_terminal\")"
    )
    val ready = generate.indexOf("markControlCodeFastReady(\"cleanup:${'$'}deferredCleanupReason\")")
    assertTrue(finalZeroAndStop >= 0)
    assertTrue(ready > finalZeroAndStop)
    assertTrue(generate.contains("panelFinalization?.safe == true"))
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
    assertTrue(service.contains("TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(service.contains("TICKET_SLIDER_PROOF_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(service.contains("CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 3_000L"))
    assertTrue(service.contains("STREAM_STALE_ENGINE_RESTART_MILLIS = 3_000L"))
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
  fun watchdogKeepsThreeSecondRecoveryButGrantsOnlyCurrentEncoderStartupGrace() {
    val recover = body(
      service,
      "private fun activeHardwareStreamStartingForRecovery",
      "private fun recoverTicketSessionReason"
    )
    val watchdog = body(service, "private fun evaluateStreamWatchdog", "private fun configMessage")
    val stale = body(
      service,
      "private fun activeStreamStaleForRecovery",
      "private fun sendStatus"
    )
    assertTrue(service.contains("STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS = 3_000L"))
    assertTrue(service.contains("STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS = 3_000L"))
    assertTrue(service.contains(
      "STREAM_WATCHDOG_STARTUP_FIRST_USEFUL_FRAME_GRACE_MILLIS =\n" +
        "      TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS"
    ))
    assertTrue(watchdog.contains("hardwareStartupFirstUsefulFramePending(health)"))
    assertTrue(watchdog.contains("waiting_startup_first_useful_frame"))
    assertTrue(recover.contains("hardwareStartupFirstUsefulFramePending(health)"))
    assertTrue(stale.contains("hardwareStartupStillPreparing(nowMillis)"))
    assertTrue(stale.contains("hardwareStartupFirstUsefulFramePending(health)"))
  }

  @Test
  fun publicOpenAndRootWakeNavigationTelemetryIsPublished() {
    val allowlist = body(service, "private fun shouldPublishTicketTraceEvent", "private fun safeErrorDetail")
    assertTrue(allowlist.contains("TicketTracePrivacy.eventName(event) != null"))
    assertEquals("wake_test", TicketTracePrivacy.eventName("wake_test"))
    assertEquals("fast_public_open_test", TicketTracePrivacy.eventName("fast_public_open_test"))
    assertEquals("root_readiness", TicketTracePrivacy.eventName("root_readiness"))
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
    assertTrue(start.indexOf("tryReuseActiveHardwareStreamBeforePreflight") < start.indexOf("runSessionStartSafetyPreflight"))
    assertTrue(reuse.contains("canReuseActiveHardwareStreamWithoutRootRevalidation"))
  }

  @Test
  fun coldStartRunsFreshSafetyChecksTogetherAfterThePackageCheck() {
    val start = body(service, "private suspend fun startTicketSessionLocked", "private fun tryReuseActiveHardwareStreamBeforePreflight")
    val preflight = body(
      service,
      "private suspend fun runSessionStartSafetyPreflight",
      "private fun startupPreflightOutcome"
    )

    val call = "val safetyPreflight = runSessionStartSafetyPreflight()"
    assertEquals(1, start.windowed(call.length, 1).count { it == call })
    assertTrue(start.indexOf("TicketPackageSupport.isInstalled") < start.indexOf("runSessionStartSafetyPreflight()"))
    assertTrue(preflight.contains("runTicketSessionStartPreflight("))
    assertTrue(preflight.contains("PhonePortraitLock.ensureVerifiedResult(inputRootExecutor)"))
    assertTrue(preflight.contains("ensureSecureWindowCaptureBypassResultForSessionStart(\"session_start\")"))
    assertTrue(preflight.contains("AtomicReference<TicketSecureWindowCaptureBypassLease?>"))
    assertTrue(preflight.contains("session_start_preflight_cancelled"))
    assertTrue(preflight.contains("lastStartupPreflight = TicketSessionStartPreflightHealth("))
    assertTrue(preflight.contains("totalMillis = totalDurationMillis"))
    assertTrue(preflight.contains("portraitMillis = portrait.second"))
    assertTrue(preflight.contains("secureCaptureMillis = secureCapture.second"))
    assertTrue(service.contains("startupPreflightOutcome = startupPreflight.outcome"))
    assertTrue(service.contains("preserveExistingLease = true"))
    assertTrue(start.contains("secureWindowCaptureBypassOwner.releaseAcquiredLease("))
    assertTrue(start.contains("shouldReleaseSessionStartSecureLease(false, safetyPreflight.secureCapture)"))
    assertTrue(start.contains("safetyPreflight.secureCapture.outcome == \"ownership_busy\""))
    assertTrue(start.contains("safetyPreflight.secureCapture.outcome == \"ownership_unproved\""))
    assertTrue(start.contains("state = \"secure_capture_ownership_unproved\""))
    assertTrue(start.contains("streamStartAdmission.claimCount() > 0L"))
    assertTrue(start.contains("ticketSpacetimeControlCodeRequestActive()"))
    assertTrue(start.contains("state = \"secure_capture_ownership_busy\""))
    assertTrue(start.contains("broadcastStatus()"))
    assertFalse(start.contains("PhonePortraitLock.force("))
    assertFalse(start.contains("PhonePortraitLock.verify("))
    assertTrue(start.indexOf("runSessionStartSafetyPreflight()") < start.indexOf("currentDisplaySize()"))
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
    assertEquals("recovery_test", TicketTracePrivacy.eventName("recovery_test"))
  }

  @Test
  fun ticketActivationMarksEachOrdinalAfterItsFenceAndBeforeTheSharedStrokeCallsite() {
    val action = body(
      service,
      "private suspend fun runTicketVisualActionV3WithCaptureLease",
      "private suspend fun proveCurrentTicketVisualAction"
    )
    val activation = body(
      service,
      "private suspend fun activateTicketFromVisualAction",
      "private suspend fun prepareExactTicketActivationDispatch"
    )
    val reconnect = action.indexOf("PhoneAutomationServiceBridge.awaitAccessibilityConnection(")
    val initialProof = action.indexOf("awaitStableTicketVisualActionObservation(")
    val fresh = activation.indexOf("recordTicketActivationFreshProof(")
    val dispatching = activation.indexOf(
      "recordTicketActivationDispatching(requireNotNull(checkpoint), ordinal)"
    )
    val stroke = activation.indexOf("PhoneAutomationServiceBridge.performTicketSliderFullStroke(")

    assertTrue(reconnect >= 0)
    assertTrue(initialProof > reconnect)
    assertTrue(fresh >= 0)
    assertTrue(dispatching > fresh)
    assertTrue(stroke > dispatching)
    assertEquals(1, Regex("performTicketSliderFullStroke\\(").findAll(activation).count())
    assertTrue(activation.contains("for (ordinal in 1..2)"))
    assertTrue(activation.contains("expectedInputFence = prepared.inputFence"))
    assertFalse(activation.contains("startTicketSliderGesture("))
    assertFalse(activation.contains("continueTicketSliderGesture("))
    assertFalse(activation.contains("endTicketSliderGesture("))
  }

  @Test
  fun startCommandIsRevalidatedImmediatelyBeforeReceiptAndDispatch() {
    val cycle = body(
      spacetimeWorker,
      "private suspend fun runCycle",
      "private suspend fun publishNextPhoneResult"
    )
    val commandLoop = cycle.substring(cycle.indexOf("for (scannedCommand in commands)"))
    val revalidate = commandLoop.indexOf("client.pendingCommandIsDispatchable(config, command.id)")
    val handoff = commandLoop.indexOf("subscribedCommandHandoff.fromPoll(command.id) {")
    val receipt = commandLoop.indexOf("noteDurableStartCommandReceipt(", handoff)
    val dispatch = commandLoop.indexOf("service.handleTicketSpacetimeCommand(command)", handoff)
    val ack = commandLoop.indexOf("client.ack(")

    assertTrue(revalidate >= 0)
    assertTrue(handoff > revalidate)
    assertTrue(receipt > handoff)
    assertTrue(dispatch > receipt)
    assertTrue(ack > dispatch)
    assertTrue(commandLoop.substring(revalidate, handoff).contains("continue"))
    assertTrue(commandLoop.substring(revalidate, handoff).contains("Instant.now()"))
    assertTrue(cycle.contains("!staleStartCommandSkipped"))
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
    assertTrue(input.contains("boundedNonTouchCommand(command, timeout), timeout"))
  }

  @Test
  fun recoveryTapsUseAnIndependentShortLivedRootLane() {
    val recovery = body(service, "private suspend fun runFastRecoveryInput", "private suspend fun runFastNonTouchWakeScript")
    assertTrue(service.contains("private val recoveryInputRootExecutor = SuRootExecutor()"))
    assertTrue(recovery.contains("recoveryInputRootExecutor.run(command, timeout)"))
    assertTrue(recovery.contains("TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS.milliseconds"))
    assertTrue(recovery.contains("runPanelDarkCommand(reason)"))
    assertFalse(service.contains("zeroTailPanelClamp"))
  }

  @Test
  fun staleInactivityStopCannotTearDownAReactivatedSession() {
    val stop = body(service, "private suspend fun stopTicketSessionIfStillInactive", "private suspend fun stopTicketSessionLocked")
    assertTrue(stop.contains("viewerInputGeneration == expectedViewerInputGeneration"))
    assertTrue(stop.contains("lastViewerInputAtMillis == expectedLastInputAtMillis"))
    assertTrue(stop.contains("TicketInactivityPolicy.shouldStop"))
    assertTrue(stop.contains("activeViewerDemand = videoClients.isNotEmpty()"))
    assertTrue(stop.contains("!ticketProofStreamAutomationOwnershipActive()"))
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
    assertTrue(disconnect.contains("clientCount = totalClientCount()"))
    assertTrue(disconnect.contains("startOwnershipActive = streamStartAdmission.claimCount() > 0L"))
    assertTrue(disconnect.contains("CLIENT_DISCONNECT_IDLE_GRACE_MILLIS"))
    assertTrue(disconnect.contains("TicketProofStreamCleanupDecision.STOP -> noteClientDetachedLocked(stopReason)"))
  }

  @Test
  fun staleDisconnectJobCannotStopAReplacementGeneration() {
    val disconnect = body(service, "private fun scheduleClientDisconnectGraceLocked", "private fun markViewerInput")
    assertTrue(disconnect.contains("val runningJob = coroutineContext[Job]"))
    assertTrue(disconnect.contains("clientDisconnectStopJob !== runningJob"))
    assertTrue(disconnect.contains("expectedSessionGeneration = expectedSessionGeneration"))
    assertTrue(disconnect.contains("currentSessionGeneration = ticketSessionGeneration"))
    assertTrue(disconnect.contains("streamActive = ticketSessionOpen()"))
    assertTrue(disconnect.contains("TicketProofStreamCleanupDecision.RETRY -> scheduleClientDisconnectGraceLocked("))
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
    assertTrue(claim.contains("sessionMutex.withLock"))
    assertTrue(claim.indexOf("sessionMutex.withLock") < claim.indexOf("streamStartAdmission.claim()"))
    assertTrue(release.contains("val remainingClaims = streamStartAdmission.release()"))
    assertTrue(release.contains("withContext(NonCancellable)"))
    assertTrue(release.contains("sessionMutex.withLock"))
    assertTrue(release.indexOf("sessionMutex.withLock") < release.indexOf("streamStartAdmission.release()"))
    assertTrue(release.contains("markViewerInput(\"control_automation_released\")"))
    assertTrue(release.contains("scheduleClientDisconnectGraceLocked()"))
  }

  @Test
  fun allPersistentRootScriptsKeepTheirDescendantDeadlineWithoutAnotherClamp() {
    val command = body(service, "private fun boundedNonTouchCommand", "private fun shellQuote")
    assertTrue(command.contains("timeout.inWholeMilliseconds - NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS"))
    assertTrue(command.contains("timeout -k 0.250s"))
    assertTrue(command.contains("shellQuote(command)"))
    val helpers = body(service, "private suspend fun runFastNonTouchInput", "private fun boundedNonTouchCommand")
    assertEquals(4, Regex("runScript\\(boundedNonTouchCommand\\(command, timeout\\), timeout\\)").findAll(helpers).count())
    assertFalse(service.contains("wrapNonTouchPanelSleepClamp"))
    assertFalse(service.contains("ticket_clamp_pid"))
    assertFalse(service.contains("PANEL_SLEEP_CLAMP_POST_MILLIS"))
  }

  @Test
  fun standaloneAuthorityLossAbortsTheWholeOperationWithoutReacquiringOrNestingTheLane() {
    val command = body(service, "private suspend fun runPanelDarkCommand", "private suspend fun runFastNonTouchInput")
    assertTrue(command.indexOf("val standalone = activeTicketActionPanelDarkLease == null") <
      command.indexOf("panelDarkCommandRunner.run(reason, command)"))
    assertTrue(command.contains("if (standalone && touchBrightnessSnapshot()?.touchBrightnessEnabled == false) return command()"))
    assertTrue(command.contains("if (standalone && result.exitCode == 46)"))
    assertTrue(command.contains("updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION"))
    assertTrue(command.contains("throw CancellationException("))
    assertTrue(command.contains("finally"))
    assertTrue(command.contains("clearNonTouchInputTailForBrowserCriticalAction("))
    assertFalse(command.contains("withOwnership"))
    assertFalse(command.contains(".acquire()"))
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
    assertTrue(health.contains("status = \"retired\""))
    assertTrue(health.contains("reason = \"ticket_action_v3_only\""))
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
