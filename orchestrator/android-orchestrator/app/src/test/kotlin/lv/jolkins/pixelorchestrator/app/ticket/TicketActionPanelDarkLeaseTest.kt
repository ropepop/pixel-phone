package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketActionPanelDarkLeaseTest {
  @Test
  fun leaseHoldsAcrossAnActionAndExactlyStopsDetachedHelperOnRelease() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    val snapshots = mutableListOf<TicketActionPanelDarkLeaseSnapshot>()
    var now = 1_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-1",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(available = true, active = false, observedAtUptimeMillis = now)
      },
      onSnapshotChanged = snapshots::add,
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    assertTrue(helper.started.isCompleted)
    assertTrue(lease.beforeMutationAllowed())
    lease.markMutationMayHaveDispatched()

    lease.release("terminal_complete")
    runCurrent()

    assertTrue(helper.completed)
    assertEquals(2, verifier.stopCalls)
    assertFalse(lease.snapshot().active)
    assertTrue(lease.snapshot().mutationMayHaveDispatched)
    assertEquals("terminal_complete", lease.snapshot().releaseReason)
    assertTrue(snapshots.any { it.active && it.lastZeroConfirmedAtUptimeMillis == 1_000L })
  }

  @Test
  fun physicalRootTouchPreemptsTheHelperAndBlocksTheNextMutationBoundary() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var now = 2_000L
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-touch",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1
    )

    // The synchronous mutation-boundary read wins even before the 20 ms monitor wakes.
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
    assertTrue(helper.completed)

    lease.release("ignored_after_preemption")
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
  }

  @Test
  fun monitorPreemptsARealTouchDuringVisualWaitingWithoutWaitingForMutation() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var now = 3_000L
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-wait",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1
    )
    now += TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS
    advanceTimeBy(TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS)
    runCurrent()

    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_preempted", lease.snapshot().releaseReason)
    assertTrue(helper.completed)
    lease.release("test_complete")
  }

  @Test
  fun activeOrUnreadyPhysicalTouchSourceFailsClosedBeforeStartingAHelper() = runTest {
    suspend fun acquireFor(
      state: PhoneAutomationRootPhysicalTouchState
    ): Pair<Boolean, SuccessfulLaunchRootExecutor> {
      val helper = SuccessfulLaunchRootExecutor()
      val lease = TicketActionPanelDarkLease(
        actionId = "action-blocked",
        scope = backgroundScope,
        clampRootExecutor = helper,
        verifyRootExecutor = ZeroRootExecutor(),
        physicalTouchState = { state },
        onSnapshotChanged = {},
        ownerProcessId = 4321,
        uptimeClock = { 4_000L }
      )
      return lease.acquire() to helper
    }

    val active = acquireFor(PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = 4_000L
    ))
    assertFalse(active.first)
    assertFalse(active.second.started.isCompleted)

    val unready = acquireFor(PhoneAutomationRootPhysicalTouchState())
    assertFalse(unready.first)
    assertFalse(unready.second.started.isCompleted)
  }

  @Test
  fun spawnAcknowledgementStillWaitsForDelayedReadinessAndTwoZeroProofs() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = SequencedVerifyRootExecutor(listOf(
      "helper_ready=0\npanel_dark=1\n",
      "helper_ready=1\npanel_dark=1\n",
      "helper_ready=1\npanel_dark=1\n"
    ))
    val lease = TicketActionPanelDarkLease(
      actionId = "action-readiness",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = 5_000L
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { 5_000L },
      helperToken = "test-readiness-token"
    )

    assertTrue(lease.acquire())
    assertEquals(3, verifier.calls)
    assertTrue(helper.started.isCompleted)
    assertTrue(lease.snapshot().active)

    lease.release("test_complete")
  }

  @Test
  fun spawnAcknowledgementFailsClosedWhenHelperNeverBecomesReady() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = SequencedVerifyRootExecutor(listOf("helper_ready=0\npanel_dark=1\n"))
    var now = 6_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-no-helper",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = now
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now.also { now += TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS } },
      helperToken = "test-never-ready-token"
    )

    assertFalse(lease.acquire())
    runCurrent()

    assertTrue(verifier.calls > 1)
    assertTrue(helper.completed)
    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_helper_unready_or_zero_unproved", lease.snapshot().failure)
    assertEquals("acquire_failed", lease.snapshot().releaseReason)
  }

  @Test
  fun completedRootLaunchDoesNotEndAnExactlyVerifiedDetachedHelper() = runTest {
    val launcher = CompletedLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-detached-helper",
      scope = backgroundScope,
      clampRootExecutor = launcher,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 7_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 7_000L },
      helperToken = "detached-helper-token"
    )

    assertTrue(lease.acquire())
    runCurrent()
    assertTrue(launcher.completed)

    // After a successful handoff, the completed Java/su launcher is no longer the ongoing helper
    // lifetime authority; exact identity and zero verification remain live for the whole action.
    advanceTimeBy(17_000L)
    runCurrent()

    assertTrue(lease.snapshot().active)
    assertEquals("", lease.snapshot().failure)
    assertTrue(lease.beforeMutationAllowed())
    lease.release("test_complete")
    assertEquals(2, verifier.stopCalls)
  }

  @Test
  fun nonzeroAndTimeoutLauncherResultsFailClosedBeforeAcquisition() = runTest {
    suspend fun assertRejected(result: RootResult, suffix: String) {
      val launcher = ImmediateResultLaunchRootExecutor(result)
      val verifier = SequencedVerifyRootExecutor(listOf("helper_ready=0\npanel_dark=0\n"))
      val lease = TicketActionPanelDarkLease(
        actionId = "action-launch-failure-$suffix",
        scope = backgroundScope,
        clampRootExecutor = launcher,
        verifyRootExecutor = verifier,
        physicalTouchState = { readyNoTouch(testScheduler.currentTime + 8_000L) },
        onSnapshotChanged = {},
        ownerProcessId = 4321,
        uptimeClock = { testScheduler.currentTime + 8_000L },
        helperToken = "launch-failure-$suffix"
      )

      assertFalse(lease.acquire())
      runCurrent()
      assertEquals("panel_dark_helper_launch_failed", lease.snapshot().failure)
      assertEquals("panel_dark_helper_launch_failed", lease.snapshot().releaseReason)
      assertEquals(result.exitCode, lease.snapshot().launchExitCode)
      assertEquals(result.durationMs, lease.snapshot().launchDurationMillis ?: -1L)
      assertEquals(0, verifier.calls)
      assertEquals(TicketActionPanelDarkLease.PANEL_DARK_LAUNCH_TIMEOUT_MILLIS, launcher.timeoutMillis)
    }

    assertRejected(
      RootResult(79, "", "nohup unavailable", "script", 1L),
      "nonzero"
    )
    assertRejected(
      RootResult(124, "", "root command timed out", "script", 2_000L),
      "timeout"
    )
    assertRejected(
      RootResult(0, "", "", "script", 1L),
      "missing-ack"
    )
  }

  @Test
  fun pendingLauncherCannotAuthorizeAndItsFailureRemainsTheFirstReason() = runTest {
    val launcher = DeferredLaunchRootExecutor()
    val verifier = SequencedVerifyRootExecutor(listOf("helper_ready=0\npanel_dark=0\n"))
    val lease = TicketActionPanelDarkLease(
      actionId = "action-first-launch-failure",
      scope = backgroundScope,
      clampRootExecutor = launcher,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 9_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 9_000L },
      helperToken = "first-launch-failure"
    )

    val acquisition = async { lease.acquire() }
    runCurrent()
    assertFalse(acquisition.isCompleted)
    assertEquals(0, verifier.calls)

    launcher.complete(
      RootResult(124, "", "root command timed out", "script", 2_000L)
    )
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS)
    runCurrent()

    assertFalse(acquisition.await())
    assertEquals("panel_dark_helper_launch_failed", lease.snapshot().failure)
    assertEquals("panel_dark_helper_launch_failed", lease.snapshot().releaseReason)
    assertEquals(124, lease.snapshot().launchExitCode)
    assertEquals(2_000L, lease.snapshot().launchDurationMillis)
    assertEquals(0, verifier.calls)
  }

  @Test
  fun persistentWorkerLaunchClosesAllRootSessionFileDescriptorsWithoutSetsid() {
    val launch = TicketActionPanelDarkLease.panelDarkLaunchScript(4321, "closed-fds-token")
    val detached = "</dev/null >/dev/null 2>&1 &"

    assertTrue(launch.contains("nohup sh -c"))
    assertFalse(launch.contains("setsid"))
    assertTrue(launch.contains(detached))
    assertEquals(1, Regex(Regex.escape(detached)).findAll(launch).count())
    assertTrue(launch.indexOf(detached) < launch.indexOf("echo helper_launched=1"))
    assertFalse(launch.contains("readiness_attempt="))
    assertFalse(launch.contains("[ -f \"\$readiness_file\" ] || exit 74"))
  }

  @Test
  fun helperBodyCannotBreakItsSingleQuotedShellArgument() {
    val launch = TicketActionPanelDarkLease.panelDarkLaunchScript(4321, "quoted-body-token")
    val prefix = "nohup sh -c '"
    val suffix = "' ${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}"
    val bodyStart = launch.indexOf(prefix)
    val bodyEnd = launch.indexOf(suffix, bodyStart + prefix.length)

    assertTrue(bodyStart >= 0)
    assertTrue(bodyEnd > bodyStart)
    val body = launch.substring(bodyStart + prefix.length, bodyEnd)
    assertFalse(body.contains('\''))
    assertTrue(body.contains("printf \"%s\\n\" \"\$1\""))
    assertTrue(body.contains("case \"\$helper_exit_code\" in \"\"|*[!0-9]*)"))
    assertTrue(body.contains("case \"\$uptime_seconds\" in \"\"|*[!0-9]*)"))
    listOf("outer launch" to launch, "inner helper" to body).forEach { (label, script) ->
      val parser = ProcessBuilder("sh", "-n", "-c", script, label)
        .redirectErrorStream(true)
        .start()
      val parserOutput = parser.inputStream.bufferedReader().use { it.readText() }
      assertEquals("$label syntax: $parserOutput", 0, parser.waitFor())
    }
  }

  @Test
  fun rootHelperIsOwnerBoundAndStartupCleanupTargetsOnlyItsExactMarker() {
    val helperToken = "test-helper-token"
    val helper = TicketActionPanelDarkLease.panelDarkLaunchScript(4321, helperToken)
    val readiness = TicketActionPanelDarkLease.panelDarkVerifyScript(helperToken)
    val cleanup = TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT

    assertTrue(helper.contains("owner_pid=4321"))
    assertTrue(helper.contains("/proc/\$owner_pid/stat"))
    assertTrue(helper.contains("owner_start"))
    assertTrue(helper.contains("shift 19"))
    assertTrue(helper.contains(TicketActionPanelDarkLease.HELPER_PROCESS_MARKER))
    assertTrue(helper.contains(helperToken))
    assertTrue(helper.contains(TicketActionPanelDarkLease.HELPER_READINESS_DIRECTORY))
    assertTrue(helper.indexOf(".launch\"") < helper.indexOf("nohup sh -c"))
    assertTrue(helper.contains("command -v nohup"))
    assertTrue(helper.contains("command -v mkfifo"))
    assertTrue(helper.contains("nohup sh -c"))
    assertFalse(helper.contains("setsid"))
    assertTrue(helper.contains("</dev/null >/dev/null 2>&1 &"))
    assertTrue(helper.contains("echo helper_launched=1"))
    assertFalse(helper.contains("readiness_attempt="))
    assertTrue(helper.indexOf("helper_launcher_pid=\$!") < helper.indexOf("echo helper_launched=1"))
    assertTrue(helper.contains("read_uptime_seconds"))
    assertTrue(helper.contains("< /proc/uptime"))
    assertTrue(helper.contains("helper_deadline_uptime=\$((helper_started_uptime + 90))"))
    assertFalse(helper.contains("remaining_writes="))
    assertFalse(helper.contains("exec sh -c"))
    assertTrue(helper.contains("trap \"\" HUP"))
    assertTrue(helper.contains("trap \"cleanup_helper_runtime; exit 0\" INT TERM"))
    assertTrue(helper.contains("trap record_helper_exit EXIT"))
    assertFalse(helper.contains("exit 0\" HUP"))
    assertTrue(helper.contains("\$helper_token.stage"))
    assertTrue(helper.contains("\$helper_token.exit"))
    assertTrue(helper.contains("\$helper_token.wait"))
    assertTrue(helper.contains("printf '%s\\n' launch_prepared"))
    assertTrue(helper.contains("write_stage child_started"))
    assertTrue(helper.contains("write_stage identity_ready"))
    assertTrue(helper.contains("write_stage panel_ready"))
    assertTrue(helper.contains("write_stage zero_written"))
    assertTrue(helper.contains("write_stage ready"))
    assertTrue(helper.contains("helper_exit_code=\"\$?\""))
    assertTrue(helper.contains("mkfifo \"\$wait_file\""))
    assertTrue(helper.contains("[ -p \"\$wait_file\" ] || exit 78"))
    assertTrue(helper.contains("exec 9<> \"\$wait_file\" || exit 78"))
    assertTrue(helper.indexOf("umask 077") < helper.indexOf("mkfifo \"\$wait_file\""))
    assertTrue(helper.contains("rm -f \"\$readiness_file\" \"\$wait_file\""))
    assertTrue(helper.indexOf("echo 0 >") < helper.indexOf("printf \"helper_pid="))
    assertEquals(5L, TicketActionPanelDarkLease.PANEL_DARK_WRITE_INTERVAL_MILLIS)
    assertTrue(
      TicketActionPanelDarkLease.PANEL_DARK_LAUNCH_TIMEOUT_MILLIS <
        TicketActionPanelDarkLease.PANEL_DARK_MAX_HOLD_MILLIS
    )
    assertTrue(
      TicketActionPanelDarkLease.PANEL_DARK_HELPER_JOIN_TIMEOUT_MILLIS >
        TicketActionPanelDarkLease.PANEL_DARK_LAUNCH_TIMEOUT_MILLIS
    )
    assertEquals(
      (TicketActionPanelDarkLease.PANEL_DARK_VERIFY_TIMEOUT_MILLIS * 2L) +
        TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS,
      TicketActionPanelDarkLease.PANEL_DARK_MUTATION_REFRESH_TIMEOUT_MILLIS
    )
    val holdLoop = helper.substringAfter("while true; do").substringBefore("done\n      '")
    assertTrue(holdLoop.contains("read -r -t0.005 -u9 helper_wait_tick"))
    assertFalse(holdLoop.contains("usleep"))
    assertFalse(holdLoop.contains("sleep 0.005"))
    assertFalse(helper.contains("cat "))
    assertFalse(helper.contains("/data/local/tmp"))

    assertTrue(readiness.contains("$helperToken.ready"))
    assertTrue(readiness.contains("exact_helper_cmdline_args_match \"\$cmdline\" || fail_readiness"))
    assertFalse(readiness.contains("grep -Fzqx"))
    assertTrue(readiness.contains("read_proc_start \"\$helper_pid\""))
    assertTrue(readiness.contains("read_proc_start \"\$owner_pid\""))
    assertTrue(readiness.contains("echo helper_ready="))
    assertTrue(readiness.contains("echo panel_dark="))
    assertTrue(readiness.contains("emit_helper_telemetry"))
    assertTrue(readiness.contains("echo helper_stage=\"\$helper_stage\""))
    assertTrue(readiness.contains("echo helper_exit_code=\"\$helper_exit_value\""))
    assertFalse(readiness.contains("cat \"\$stage_file\""))
    assertFalse(readiness.contains("cat \"\$exit_file\""))
    assertFalse(readiness.contains("cat "))
    assertTrue(readiness.contains("IFS= read -r current < \"\$panel/brightness\""))
    assertTrue(readiness.contains("IFS= read -r actual < \"\$panel/actual_brightness\""))

    assertTrue(cleanup.contains("/*.ready"))
    assertTrue(cleanup.contains("/*.launch"))
    assertTrue(cleanup.contains("/*.stage"))
    assertTrue(cleanup.contains("/*.exit"))
    assertTrue(cleanup.contains("/*.wait"))
    assertTrue(cleanup.contains("cleanup_orphan_files"))
    assertTrue(cleanup.contains("\"\$exit_file\" \"\$wait_file\""))
    assertFalse(cleanup.contains("cat "))
    assertTrue(cleanup.contains("candidate_count="))
    assertTrue(cleanup.contains("-gt ${TicketActionPanelDarkLease.MAX_HELPER_READINESS_FILES}"))
    assertTrue(cleanup.indexOf("candidate_limit_exceeded=") < cleanup.indexOf("kill -TERM"))
    assertTrue(cleanup.contains("exact_helper_cmdline_args_match \"\$cmdline\" || continue"))
    assertTrue(cleanup.contains("kill -TERM"))
    assertTrue(cleanup.contains("cleaned="))
    assertFalse(readiness.contains("/proc/[0-9]*/cmdline"))
    assertTrue(cleanup.contains("/proc/[0-9]*/cmdline"))
    assertFalse(cleanup.contains("grep -Fzqx"))
    val globalScan = cleanup.substringAfter("for cmdline in /proc/[0-9]*/cmdline; do")
    val exactCandidate = globalScan.indexOf(
      "exact_helper_cmdline_args_match \"\$cmdline\" || continue"
    )
    val candidateStartRead = globalScan.indexOf("read_proc_start \"\$candidate\"")
    assertTrue(exactCandidate >= 0)
    assertTrue(candidateStartRead > exactCandidate)
    assertFalse(cleanup.contains("read_proc_start \"\$owner_pid\""))
    assertFalse(readiness.contains("pidof sh"))
    assertFalse(cleanup.contains("pidof sh"))
  }

  @Test
  fun exactCmdlineMatcherUsesBuiltinNulArgumentsAndAllFourExactFlags() {
    val matcher = TicketActionPanelDarkLease.EXACT_HELPER_CMDLINE_MATCH_FUNCTION

    assertTrue(matcher.contains("while IFS= read -r -d '' cmdline_arg"))
    assertTrue(matcher.contains(
      "[ \"\$cmdline_arg\" = '${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}' ]"
    ))
    assertTrue(matcher.contains("[ \"\$cmdline_arg\" = \"\$helper_token\" ]"))
    assertTrue(matcher.contains("[ \"\$cmdline_arg\" = \"\$owner_pid\" ]"))
    assertTrue(matcher.contains("[ \"\$cmdline_arg\" = \"\$owner_start\" ]"))
    assertTrue(matcher.contains("[ \"\$marker_arg_found\" = \"1\" ]"))
    assertTrue(matcher.contains("[ \"\$token_arg_found\" = \"1\" ]"))
    assertTrue(matcher.contains("[ \"\$owner_pid_arg_found\" = \"1\" ]"))
    assertTrue(matcher.contains("[ \"\$owner_start_arg_found\" = \"1\" ]"))
    assertFalse(matcher.contains("grep"))
    assertFalse(matcher.contains("cat "))
  }

  @Test
  fun helperLifecycleAndVerifierDiagnosticsAreSanitizedAndDoNotAuthorizeMutation() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(listOf(
      RootResult(
        exitCode = 74,
        stdout = "helper_stage=identity_ready\nhelper_exit_code=77\nhelper_ready=0\npanel_dark=0\n",
        stderr = "private root failure text",
        command = "private root command",
        durationMs = 19L
      )
    ))
    var now = 6_500L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-helper-lifecycle",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now.also { now += TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS } },
      helperToken = "helper-lifecycle-token"
    )

    assertFalse(lease.acquire())
    assertFalse(lease.beforeMutationAllowed())
    assertEquals("helper_unready", lease.snapshot().lastVerifierClassification)
    assertEquals(74, lease.snapshot().lastVerifierExitCode)
    assertEquals(19L, lease.snapshot().lastVerifierDurationMillis)
    assertEquals("identity_ready", lease.snapshot().helperStage)
    assertEquals(77, lease.snapshot().helperExitCode)
    assertFalse(lease.snapshot().toString().contains("private root"))
  }

  @Test
  fun preReadinessTouchWaitsForLateLauncherThenPerformsSecondExactStop() = runTest {
    val helper = DelayedCancellationIgnoringLaunchRootExecutor(delayMillis = 1_500L)
    val verifier = LateSpawnRecordingVerifyRootExecutor(helper)
    var touch = readyNoTouch(testScheduler.currentTime + 7_000L)
    val lease = TicketActionPanelDarkLease(
      actionId = "action-noncancellable-helper",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 7_000L },
      helperToken = "exact-release-token"
    )

    val acquisition = async { lease.acquire() }
    runCurrent()
    assertTrue(helper.started.isCompleted)
    assertFalse(acquisition.isCompleted)
    assertEquals(0, verifier.verificationCalls)

    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = testScheduler.currentTime + 7_001L
    )
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS)
    runCurrent()

    assertFalse(acquisition.isCompleted)
    assertEquals(1, verifier.retainedMarkerStopCalls)
    assertEquals(0, verifier.finalMarkerStopCalls)

    // The root transport ignores cancellation and spawns after more than the old one-second join
    // bound. Acquisition must remain blocked until that launcher has quiesced and a second exact
    // scan has removed the late child and launch marker.
    advanceTimeBy(1_474L)
    runCurrent()
    assertFalse(acquisition.isCompleted)
    advanceTimeBy(1L)
    runCurrent()

    assertFalse(acquisition.await())
    assertEquals(2, verifier.stopCalls)
    assertEquals(1, verifier.retainedMarkerStopCalls)
    assertEquals(1, verifier.finalMarkerStopCalls)
    assertTrue(verifier.finalStopObservedLateSpawn)
    assertTrue(helper.completed)
    assertFalse(lease.snapshot().active)
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_during_acquire", lease.snapshot().releaseReason)
    assertEquals("", lease.snapshot().failure)
    assertEquals(0, lease.snapshot().launchExitCode)
    assertEquals(1_500L, lease.snapshot().launchDurationMillis)
  }

  @Test
  fun finalConvergenceTailKeepsTheExactHelperActiveBeforeRelease() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-final-tail",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 50_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 50_000L },
      helperToken = "final-tail-token"
    )

    assertTrue(lease.acquire())
    val release = async {
      lease.releaseAfterFinalConvergence("terminal_complete")
    }
    runCurrent()
    assertFalse(release.isCompleted)
    assertTrue(lease.snapshot().active)
    assertEquals(0, verifier.stopCalls)

    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS - 1L)
    runCurrent()
    assertFalse(release.isCompleted)
    assertTrue(lease.snapshot().active)
    assertEquals(0, verifier.stopCalls)

    advanceTimeBy(1L)
    runCurrent()
    val finalization = release.await()
    assertTrue(helper.completed)
    assertEquals(2, verifier.stopCalls)
    assertTrue(finalization.safe)
    assertTrue(finalization.freshZeroProven)
    assertTrue(finalization.exactHelperStopProven)
    assertEquals("terminal_complete", lease.snapshot().releaseReason)
  }

  @Test
  fun physicalTouchStillPreemptsTheFinalConvergenceTail() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var touch = readyNoTouch(testScheduler.currentTime + 60_000L)
    val lease = TicketActionPanelDarkLease(
      actionId = "action-tail-touch",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 60_000L },
      helperToken = "tail-touch-token"
    )

    assertTrue(lease.acquire())
    lease.markMutationMayHaveDispatched()
    val tailStartedAt = testScheduler.currentTime
    val release = async {
      lease.releaseAfterFinalConvergence("terminal_complete")
    }
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = testScheduler.currentTime + 60_001L
    )
    advanceTimeBy(TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS)
    runCurrent()
    val finalization = release.await()

    assertFalse(finalization.safe)
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertTrue(finalization.snapshot.mutationMayHaveDispatched)
    assertTrue(lease.snapshot().releaseReason in setOf(
      "physical_touch_preempted",
      "physical_touch_at_mutation_boundary"
    ))
    assertTrue(helper.completed)
    assertTrue(
      testScheduler.currentTime - tailStartedAt <
        TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS
    )
  }

  @Test
  fun finalConvergenceRequiresAFreshExactZeroProofAndOverridesEarlierSuccess() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ToggleZeroRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-final-zero-loss",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 70_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 70_000L },
      helperToken = "final-zero-loss-token"
    )

    assertTrue(lease.acquire())
    lease.markMutationMayHaveDispatched()
    val release = async { lease.releaseAfterFinalConvergence("terminal_complete") }
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS - 1L)
    runCurrent()
    verifier.panelDark = false
    advanceTimeBy(1L)
    runCurrent()
    val finalization = release.await()

    assertFalse(finalization.safe)
    assertFalse(finalization.freshZeroProven)
    assertTrue(finalization.exactHelperStopProven)
    assertEquals("panel_dark_zero_lost", finalization.snapshot.failure)
    assertTrue(finalization.snapshot.mutationMayHaveDispatched)
    assertTrue(helper.completed)
  }

  @Test
  fun finalConvergenceFailsClosedWhenExactHelperShutdownCannotBeProved() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = FailingStopVerifyRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-final-stop-unproved",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 80_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 80_000L },
      helperToken = "final-stop-unproved-token"
    )

    assertTrue(lease.acquire())
    val release = async { lease.releaseAfterFinalConvergence("terminal_complete") }
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS)
    runCurrent()
    val finalization = release.await()

    assertFalse(finalization.safe)
    assertTrue(finalization.freshZeroProven)
    assertFalse(finalization.exactHelperStopProven)
    assertEquals("panel_dark_helper_stop_unproved", finalization.snapshot.failure)
  }

  @Test
  fun physicalTouchDuringExactHelperShutdownStillOverridesTheTerminal() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var touch = readyNoTouch(testScheduler.currentTime + 90_000L)
    val verifier = TouchOnStopVerifyRootExecutor {
      touch = PhoneAutomationRootPhysicalTouchState(
        available = true,
        active = true,
        observedAtUptimeMillis = testScheduler.currentTime + 90_001L
      )
    }
    val lease = TicketActionPanelDarkLease(
      actionId = "action-touch-during-stop",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 90_000L },
      helperToken = "touch-during-stop-token"
    )

    assertTrue(lease.acquire())
    lease.markMutationMayHaveDispatched()
    val release = async { lease.releaseAfterFinalConvergence("terminal_complete") }
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS)
    runCurrent()
    val finalization = release.await()

    assertFalse(finalization.safe)
    assertTrue(finalization.freshZeroProven)
    assertTrue(finalization.exactHelperStopProven)
    assertTrue(finalization.snapshot.physicalTouchPreempted)
    assertEquals("physical_touch_at_mutation_boundary", finalization.snapshot.releaseReason)
  }

  @Test
  fun slowExactHelperShutdownDoesNotReverifyItsDeletedIdentityFiles() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var now = 95_000L
    val verifier = HelperAbsentAfterSlowStopRootExecutor {
      now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    }
    val lease = TicketActionPanelDarkLease(
      actionId = "action-slow-final-stop",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "slow-final-stop-token"
    )

    assertTrue(lease.acquire())
    val release = async { lease.releaseAfterFinalConvergence("terminal_complete") }
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS)
    runCurrent()
    val finalization = release.await()

    assertTrue(finalization.safe)
    assertTrue(finalization.freshZeroProven)
    assertTrue(finalization.exactHelperStopProven)
    assertEquals(0, verifier.verificationsAfterStop)
    assertEquals("", finalization.snapshot.failure)
    assertEquals("terminal_complete", finalization.snapshot.releaseReason)
  }

  @Test
  fun exactStopScriptValidatesIdentityThenRemovesReadinessAndLaunchMarker() {
    val helperToken = "exact-stop-token"
    val stop = TicketActionPanelDarkLease.panelDarkStopScript(helperToken)

    assertTrue(stop.contains("$helperToken.ready"))
    assertTrue(stop.contains("$helperToken.launch"))
    assertTrue(stop.contains("$helperToken.stage"))
    assertTrue(stop.contains("$helperToken.exit"))
    assertTrue(stop.contains("$helperToken.wait"))
    assertTrue(stop.contains("read_proc_start \"\$helper_pid\""))
    assertTrue(stop.contains("exact_helper_cmdline_args_match \"\$cmdline\" || return 1"))
    assertTrue(stop.contains("exact_helper_cmdline_args_match \"\$cmdline\" || continue"))
    assertFalse(stop.contains("grep -Fzqx"))
    assertTrue(stop.indexOf("exact_helper_matches || stop_failed") < stop.indexOf("kill -TERM"))
    assertTrue(stop.contains("kill -KILL"))
    assertTrue(stop.contains("rm -f \"\$readiness_file\""))
    assertTrue(stop.contains("[ ! -e \"\$readiness_file\" ]"))
    assertTrue(stop.contains("echo helper_stopped=1"))
    assertTrue(stop.contains("echo readiness_removed=1"))
    assertTrue(stop.contains("echo telemetry_removed=1"))
    assertTrue(stop.contains("echo wait_fifo_removed=1"))
    assertTrue(stop.contains("\"\$wait_file\" 2>/dev/null || stop_failed"))
    assertFalse(stop.contains("cat "))
    assertTrue(stop.contains("echo launch_marker_removed=1"))
    assertTrue(stop.contains("/proc/[0-9]*/cmdline"))
    val globalScan = stop.substringAfter("for cmdline in /proc/[0-9]*/cmdline; do")
    val exactCandidate = globalScan.indexOf(
      "exact_helper_cmdline_args_match \"\$cmdline\" || continue"
    )
    val candidateStartRead = globalScan.indexOf("read_proc_start \"\$candidate\"")
    assertTrue(exactCandidate >= 0)
    assertTrue(candidateStartRead > exactCandidate)
    assertFalse(stop.contains("pidof"))
  }

  @Test
  fun unprovedExactStopFailsClosedEvenWhenCoroutineCancellationCompletes() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = FailingStopVerifyRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stop-unproved",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = 8_000L
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { 8_000L },
      helperToken = "unproved-stop-token"
    )

    assertTrue(lease.acquire())
    lease.release("terminal_complete")
    runCurrent()

    assertTrue(helper.completed)
    assertTrue(verifier.stopCalls >= 1)
    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_helper_stop_unproved", lease.snapshot().failure)
    assertEquals("panel_dark_helper_stop_unproved", lease.snapshot().releaseReason)
  }

  @Test
  fun oneUnavailableVerificationKeepsARecentlyProvenLeaseActiveAndCanRecover() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(
        provenZeroResult(),
        provenZeroResult(),
        unavailableResult(),
        provenZeroResult()
      )
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-transient-verifier",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 10_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 10_000L },
      helperToken = "transient-verifier-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()
    assertTrue(lease.snapshot().active)
    assertEquals("", lease.snapshot().failure)

    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()
    assertTrue(lease.snapshot().active)
    assertEquals(testScheduler.currentTime + 10_000L, lease.snapshot().lastZeroConfirmedAtUptimeMillis)

    lease.release("test_complete")
  }

  @Test
  fun verifierUnavailabilityFailsClosedOnceTheLastZeroProofIsStale() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), unavailableResult())
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-verifier",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 20_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 20_000L },
      helperToken = "stale-verifier-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(
      TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS +
        TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS + 1L
    )
    runCurrent()

    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_verifier_unavailable", lease.snapshot().failure)
    assertEquals("panel_dark_zero_proof_stale", lease.snapshot().releaseReason)
    assertTrue(helper.completed)
    lease.release("test_complete")
  }

  @Test
  fun aDefinitiveNonzeroPanelReadingStillFailsImmediately() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), nonzeroPanelResult())
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-panel-nonzero",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 30_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 30_000L },
      helperToken = "panel-nonzero-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()

    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_zero_lost", lease.snapshot().failure)
    assertEquals("panel_dark_zero_lost", lease.snapshot().releaseReason)
    assertTrue(helper.completed)
    lease.release("test_complete")
  }

  @Test
  fun aStaleZeroProofRequiresFreshExactVerificationAtTheMutationBoundary() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    var now = 40_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-mutation",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-mutation-token"
    )

    assertTrue(lease.acquire())
    assertEquals(2, verifier.verificationCalls)
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    assertTrue(lease.beforeMutationAllowed())
    runCurrent()

    assertEquals(3, verifier.verificationCalls)
    assertEquals(now, lease.snapshot().lastZeroConfirmedAtUptimeMillis)
    assertEquals("", lease.snapshot().failure)
    assertTrue(lease.snapshot().active)
    lease.release("test_complete")
  }

  @Test
  fun staleMutationBoundaryStillFailsClosedWhenFreshVerificationIsUnavailable() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), unavailableResult())
    )
    var now = 50_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-refresh-unavailable",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-refresh-unavailable-token"
    )

    assertTrue(lease.acquire())
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()

    assertEquals(3, verifier.verificationCalls)
    assertEquals("panel_dark_verifier_unavailable", lease.snapshot().failure)
    assertEquals("panel_dark_zero_proof_stale_at_mutation_boundary", lease.snapshot().releaseReason)
    lease.release("test_complete")
  }

  @Test
  fun staleMutationBoundaryStillFailsImmediatelyOnAFreshNonzeroReading() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), nonzeroPanelResult())
    )
    var now = 60_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-refresh-nonzero",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-refresh-nonzero-token"
    )

    assertTrue(lease.acquire())
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()

    assertEquals(3, verifier.verificationCalls)
    assertEquals("panel_dark_zero_lost", lease.snapshot().failure)
    assertEquals("panel_dark_zero_lost", lease.snapshot().releaseReason)
    lease.release("test_complete")
  }

  @Test
  fun staleMutationBoundaryStillFailsImmediatelyWhenTheExactHelperIsUnready() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), helperUnreadyResult())
    )
    var now = 65_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-refresh-helper-unready",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-refresh-helper-unready-token"
    )

    assertTrue(lease.acquire())
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()

    assertEquals(3, verifier.verificationCalls)
    assertEquals("panel_dark_helper_identity_lost", lease.snapshot().failure)
    assertEquals("panel_dark_helper_identity_lost", lease.snapshot().releaseReason)
    lease.release("test_complete")
  }

  @Test
  fun physicalTouchDuringAStaleBoundaryRefreshOverridesANewZeroProof() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = BlockingBoundaryVerifyRootExecutor()
    var now = 70_000L
    var touch = readyNoTouch(now)
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-refresh-touch",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-refresh-touch-token"
    )

    assertTrue(lease.acquire())
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    val allowed = async { lease.beforeMutationAllowed() }
    runCurrent()
    assertTrue(verifier.boundaryVerificationStarted.isCompleted)

    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1L
    )
    verifier.boundaryVerificationResult.complete(provenZeroResult())
    runCurrent()

    assertFalse(allowed.await())
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
    lease.release("test_complete")
  }

  @Test
  fun controlCodeCleanupCanCommitOnlyAfterBothSurfaceAndPanelFinalizationProofs() {
    val safe = TicketActionPanelDarkLeaseFinalization(
      safe = true,
      freshZeroProven = true,
      exactHelperStopProven = true,
      snapshot = TicketActionPanelDarkLeaseSnapshot(releaseReason = "terminal")
    )
    val unsafe = safe.copy(safe = false, freshZeroProven = false)

    assertTrue(ticketControlCodeCleanupMayCommitAfterPanelFinalization(true, safe))
    assertFalse(ticketControlCodeCleanupMayCommitAfterPanelFinalization(false, safe))
    assertFalse(ticketControlCodeCleanupMayCommitAfterPanelFinalization(true, unsafe))
    assertFalse(ticketControlCodeCleanupMayCommitAfterPanelFinalization(true, null))

    assertEquals(
      "control_code_cleanup_attention_needed",
      ticketScheduledControlCleanupFailureReason(
        leaseAcquired = true,
        finalizationSafe = true,
        mutationMayHaveDispatched = true
      )
    )
    assertEquals(
      "control_code_panel_dark_unavailable",
      ticketScheduledControlCleanupFailureReason(
        leaseAcquired = false,
        finalizationSafe = false,
        mutationMayHaveDispatched = false
      )
    )
    assertEquals(
      "control_code_cleanup_checkpoint_clear_unproved",
      ticketScheduledControlCleanupFailureReason(
        leaseAcquired = true,
        finalizationSafe = true,
        mutationMayHaveDispatched = false
      )
    )
  }

  @Test
  fun successfulVisualProofMustStillMatchActionGenerationEpochAndKeyframeAfterTail() {
    fun current(
      proofActionId: String = "action-9",
      expectedActionId: String = "action-9",
      proofGeneration: Long = 41L,
      currentGeneration: Long = 41L,
      proofStreamEpoch: Long = 7L,
      proofFrameSequence: Long = 100L,
      currentStreamEpoch: Long = 7L,
      currentFrameSequence: Long = 105L,
      latestKeyFrameEpoch: Long = 7L,
      latestKeyFrameSequence: Long = 103L
    ) = ticketVisualSuccessProofCurrentAfterPanelFinalization(
      proofActionId,
      expectedActionId,
      proofGeneration,
      currentGeneration,
      proofStreamEpoch,
      proofFrameSequence,
      currentStreamEpoch,
      currentFrameSequence,
      latestKeyFrameEpoch,
      latestKeyFrameSequence
    )

    assertTrue(current())
    assertFalse(current(proofActionId = "old-action"))
    assertFalse(current(currentGeneration = 42L))
    assertFalse(current(currentStreamEpoch = 8L))
    assertFalse(current(currentFrameSequence = 99L))
    assertFalse(current(latestKeyFrameEpoch = 8L))
    assertFalse(current(latestKeyFrameSequence = 99L))
    assertFalse(current(proofStreamEpoch = 0L))
    assertFalse(current(proofFrameSequence = 0L))
  }

  private class SuccessfulLaunchRootExecutor : RootExecutor {
    val started = CompletableDeferred<Unit>()
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      started.complete(Unit)
      completed = true
      return RootResult(
        exitCode = 0,
        stdout = "helper_launched=1\n",
        stderr = "",
        command = "script",
        durationMs = 1L
      )
    }
  }

  private class CompletedLaunchRootExecutor : RootExecutor {
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      completed = true
      return RootResult(
        exitCode = 0,
        stdout = "helper_launched=1\n",
        stderr = "",
        command = "script",
        durationMs = 1L
      )
    }
  }

  private class ImmediateResultLaunchRootExecutor(
    private val result: RootResult
  ) : RootExecutor {
    var timeoutMillis = 0L

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      timeoutMillis = timeout.inWholeMilliseconds
      return result
    }
  }

  private class DeferredLaunchRootExecutor : RootExecutor {
    private val result = CompletableDeferred<RootResult>()

    fun complete(value: RootResult) {
      result.complete(value)
    }

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult = result.await()
  }

  private class ZeroRootExecutor : RootExecutor {
    var stopCalls = 0
    var verificationCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        stopCalls += 1
      } else {
        verificationCalls += 1
      }
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class ToggleZeroRootExecutor : RootExecutor {
    var panelDark = true
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return when {
        isStop -> RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
        panelDark -> provenZeroResult()
        else -> nonzeroPanelResult()
      }
    }
  }

  private class TouchOnStopVerifyRootExecutor(
    private val onStop: () -> Unit
  ) : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) onStop()
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class HelperAbsentAfterSlowStopRootExecutor(
    private val onFirstStop: () -> Unit
  ) : RootExecutor {
    private var stopped = false
    var verificationsAfterStop = 0
      private set

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        if (!stopped) {
          stopped = true
          onFirstStop()
        }
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      if (stopped) {
        verificationsAfterStop += 1
        return helperUnreadyResult()
      }
      return provenZeroResult()
    }
  }

  private class SequencedVerifyRootExecutor(
    private val outputs: List<String>
  ) : RootExecutor {
    var calls: Int = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      val output = outputs.getOrElse(calls) { outputs.last() }
      calls += 1
      return RootResult(
        exitCode = 0,
        stdout = output,
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class DelayedCancellationIgnoringLaunchRootExecutor(
    private val delayMillis: Long
  ) : RootExecutor {
    val started = CompletableDeferred<Unit>()
    var lateSpawned = false
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      started.complete(Unit)
      return try {
        withContext(NonCancellable) {
          delay(delayMillis)
        }
        lateSpawned = true
        RootResult(
          exitCode = 0,
          stdout = "helper_launched=1\n",
          stderr = "",
          command = "script",
          durationMs = delayMillis
        )
      } finally {
        completed = true
      }
    }
  }

  private class LateSpawnRecordingVerifyRootExecutor(
    private val launcher: DelayedCancellationIgnoringLaunchRootExecutor
  ) : RootExecutor {
    var stopCalls = 0
    var retainedMarkerStopCalls = 0
    var finalMarkerStopCalls = 0
    var verificationCalls = 0
    var finalStopObservedLateSpawn = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        stopCalls += 1
        if (script.contains("remove_launch=0")) retainedMarkerStopCalls += 1
        if (script.contains("remove_launch=1")) {
          finalMarkerStopCalls += 1
          finalStopObservedLateSpawn = launcher.lateSpawned
        }
      } else {
        verificationCalls += 1
      }
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class FailingStopVerifyRootExecutor : RootExecutor {
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return RootResult(
        exitCode = if (isStop) 74 else 0,
        stdout = if (isStop) {
          "helper_stopped=0\nreadiness_removed=0\nlaunch_marker_removed=0\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class ResultSequencedVerifyRootExecutor(
    private val results: List<RootResult>
  ) : RootExecutor {
    var verificationCalls = 0
      private set

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      return results.getOrElse(verificationCalls++) { results.last() }
    }
  }

  private class BlockingBoundaryVerifyRootExecutor : RootExecutor {
    var verificationCalls = 0
      private set
    val boundaryVerificationStarted = CompletableDeferred<Unit>()
    val boundaryVerificationResult = CompletableDeferred<RootResult>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      verificationCalls += 1
      if (verificationCalls <= 2) return provenZeroResult()
      boundaryVerificationStarted.complete(Unit)
      return boundaryVerificationResult.await()
    }
  }

  private companion object {
    fun readyNoTouch(now: Long) = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )

    fun provenZeroResult() = RootResult(
      exitCode = 0,
      stdout = "helper_ready=1\npanel_dark=1\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )

    fun unavailableResult() = RootResult(
      exitCode = 124,
      stdout = "",
      stderr = "root command timed out",
      command = "script",
      durationMs = TicketActionPanelDarkLease.PANEL_DARK_VERIFY_TIMEOUT_MILLIS
    )

    fun helperUnreadyResult() = RootResult(
      exitCode = 74,
      stdout = "helper_ready=0\npanel_dark=1\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )

    fun nonzeroPanelResult() = RootResult(
      exitCode = 1,
      stdout = "helper_ready=1\npanel_dark=0\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )
  }

}
