package lv.jolkins.pixelorchestrator.app.phoneautomation

import java.io.StringReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootTouchMonitorTest {
  @Test
  fun parseTouchDevicesPrefersPixelTouchscreenThenFallsBackToBestGenericCandidate() {
    val devices = RootTouchDeviceDiscovery.parseTouchDevices(
      """
      add device 1: /dev/input/event0
        name: "gpio-keys"
        events:
          KEY (0001): KEY_POWER
      add device 2: /dev/input/event2
        name: "fts_touchpanel"
        input props:
          INPUT_PROP_DIRECT
        events:
          ABS (0003): ABS_MT_SLOT
            ABS_MT_POSITION_X
            ABS_MT_POSITION_Y
          KEY (0001): BTN_TOUCH
      add device 3: /dev/input/event3
        name: "synaptics_tcm_touch"
        input props:
          INPUT_PROP_DIRECT
        events:
          ABS (0003): ABS_MT_SLOT
            ABS_MT_POSITION_X
            ABS_MT_POSITION_Y
          KEY (0001): BTN_TOUCH
      """.trimIndent()
    )

    assertEquals("synaptics_tcm_touch", devices.first().name)
    assertEquals("/dev/input/event2", devices[1].path)
  }

  @Test
  fun btnTouchDownAndUpChangesActiveTouchCount() {
    val tracker = RootTouchStateTracker()

    assertEquals(
      1,
      tracker.consumeLine("[   1.000000] /dev/input/event2: EV_KEY BTN_TOUCH DOWN")
    )
    assertEquals(
      0,
      tracker.consumeLine("[   1.000010] /dev/input/event2: EV_KEY BTN_TOUCH UP")
    )
  }

  @Test
  fun multitouchTrackingIdsCountOverlappingTouchesCorrectly() {
    val tracker = RootTouchStateTracker()

    tracker.consumeLine("[   1.000000] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000000")
    assertEquals(
      1,
      tracker.consumeLine("[   1.000001] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 0000000a")
    )
    tracker.consumeLine("[   1.000002] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000001")
    assertEquals(
      2,
      tracker.consumeLine("[   1.000003] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 0000000b")
    )
    tracker.consumeLine("[   1.000004] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000000")
    assertEquals(
      1,
      tracker.consumeLine("[   1.000005] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID ffffffff")
    )
    tracker.consumeLine("[   1.000006] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000001")
    assertEquals(
      0,
      tracker.consumeLine("[   1.000007] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID ffffffff")
    )
  }

  @Test
  fun androidRootTouchMonitorSwitchesToTheNextCandidateWhenThePrimaryFails() = runTest {
    val processFactory = FakeRootTouchProcessFactory(
      capabilitiesOutput = """
        add device 1: /dev/input/event2
          name: "synaptics_tcm_touch"
          input props:
            INPUT_PROP_DIRECT
          events:
            ABS (0003): ABS_MT_SLOT
              ABS_MT_POSITION_X
              ABS_MT_POSITION_Y
            KEY (0001): BTN_TOUCH
        add device 2: /dev/input/event4
          name: "fts_touchpanel"
          input props:
            INPUT_PROP_DIRECT
          events:
            ABS (0003): ABS_MT_SLOT
              ABS_MT_POSITION_X
              ABS_MT_POSITION_Y
            KEY (0001): BTN_TOUCH
      """.trimIndent(),
      monitorOutputs = mapOf(
        "/dev/input/event2" to FakeMonitorProcessOutput(
          stdout = "",
          stderr = "primary failed",
          exitCode = 1
        ),
        "/dev/input/event4" to FakeMonitorProcessOutput(
          stdout = """
            [   1.000000] EV_KEY BTN_TOUCH DOWN
            [   1.000010] EV_KEY BTN_TOUCH UP
          """.trimIndent(),
          stderr = "",
          exitCode = 0
        )
      )
    )
    val monitor = AndroidRootTouchMonitor(
      ioDispatcher = UnconfinedTestDispatcher(testScheduler),
      processFactory = processFactory,
      uptimeClock = { testScheduler.currentTime }
    )
    val observedEvents = mutableListOf<RootTouchEvent>()
    val job = backgroundScope.launch {
      monitor.events.toList(observedEvents)
    }

    advanceUntilIdle()
    job.join()

    assertEquals(5, observedEvents.size)
    assertEquals(
      RootTouchEvent.SourceSelected(
        RootTouchDevice(path = "/dev/input/event2", name = "synaptics_tcm_touch", score = 109)
      ),
      observedEvents[0]
    )
    assertEquals(
      RootTouchEvent.SourceSelected(
        RootTouchDevice(path = "/dev/input/event4", name = "fts_touchpanel", score = 9)
      ),
      observedEvents[1]
    )
    assertEquals(1, (observedEvents[2] as RootTouchEvent.TouchCountChanged).activeTouchCount)
    assertEquals(0, (observedEvents[3] as RootTouchEvent.TouchCountChanged).activeTouchCount)
    assertEquals(
      RootTouchEvent.FatalError("The root touch monitor exited with code 0"),
      observedEvents[4]
    )
    assertNull(monitor.selectedDevice())
  }

  @Test
  fun rootTouchSnapshotTracksButtonAndSlotState() {
    val tracker = RootTouchStateTracker()

    tracker.consumeLine("[   1.000000] /dev/input/event2: EV_KEY BTN_TOUCH DOWN")
    assertTrue(tracker.isTouchButtonActive())
    assertEquals(0, tracker.activeSlotCount())

    tracker.consumeLine("[   1.000001] /dev/input/event2: EV_ABS ABS_MT_SLOT 00000000")
    tracker.consumeLine("[   1.000002] /dev/input/event2: EV_ABS ABS_MT_TRACKING_ID 0000000a")
    assertEquals(1, tracker.activeSlotCount())
    assertEquals(1, tracker.rawActiveTouchCount())
  }

  @Test
  fun touchCountEventsCarrySnapshotDetails() = runTest {
    val processFactory = FakeRootTouchProcessFactory(
      capabilitiesOutput = """
        add device 1: /dev/input/event2
          name: "synaptics_tcm_touch"
          input props:
            INPUT_PROP_DIRECT
          events:
            ABS (0003): ABS_MT_SLOT
              ABS_MT_POSITION_X
              ABS_MT_POSITION_Y
            KEY (0001): BTN_TOUCH
      """.trimIndent(),
      monitorOutputs = mapOf(
        "/dev/input/event2" to FakeMonitorProcessOutput(
          stdout = """
            [   1.000000] EV_KEY BTN_TOUCH DOWN
          """.trimIndent(),
          stderr = "",
          exitCode = 0
        )
      )
    )
    val monitor = AndroidRootTouchMonitor(
      ioDispatcher = UnconfinedTestDispatcher(testScheduler),
      processFactory = processFactory,
      uptimeClock = { testScheduler.currentTime }
    )
    val observedEvents = mutableListOf<RootTouchEvent>()
    val job = backgroundScope.launch {
      monitor.events.toList(observedEvents)
    }

    advanceUntilIdle()
    job.join()

    val touchEvent = observedEvents[1] as RootTouchEvent.TouchCountChanged
    assertEquals(1, touchEvent.activeTouchCount)
    assertTrue(touchEvent.snapshot.btnTouchActive)
    assertEquals("synaptics_tcm_touch", touchEvent.snapshot.selectedDevice?.name)
  }

  @Test
  fun ignoresLinesThatDoNotMatchGeteventTouchFormat() {
    val tracker = RootTouchStateTracker()

    assertEquals(null, tracker.consumeLine("garbage"))
    assertTrue(
      RootTouchLineParser.parse("[   1.000000] /dev/input/event2: EV_KEY BTN_TOUCH DOWN") != null
    )
    assertTrue(
      RootTouchLineParser.parse("[   1.000000] EV_KEY BTN_TOUCH DOWN") != null
    )
  }
}

private class FakeRootTouchProcessFactory(
  private val capabilitiesOutput: String,
  private val monitorOutputs: Map<String, FakeMonitorProcessOutput>
) : RootTouchProcessFactory {
  override fun start(command: String): RootTouchProcess {
    return when {
      command == "getevent -lp" -> FakeRootTouchProcess(
        stdout = capabilitiesOutput,
        stderr = "",
        exitCode = 0
      )

      command.startsWith("exec getevent -lt ") -> {
        val path = command.substringAfter("exec getevent -lt ").trim().trim('\'')
        val output = monitorOutputs.getValue(path)
        FakeRootTouchProcess(
          stdout = output.stdout,
          stderr = output.stderr,
          exitCode = output.exitCode
        )
      }

      else -> error("Unexpected root touch command: $command")
    }
  }
}

private data class FakeMonitorProcessOutput(
  val stdout: String,
  val stderr: String,
  val exitCode: Int
)

private class FakeRootTouchProcess(
  stdout: String,
  stderr: String,
  private val exitCode: Int
) : RootTouchProcess {
  override val stdout = StringReader(stdout).buffered()
  override val stderr = StringReader(stderr).buffered()

  override fun waitFor(): Int = exitCode

  override fun destroy() = Unit
}
