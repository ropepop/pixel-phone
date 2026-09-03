package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationViviLoginInteractionTest {
  @Test
  fun staleActivationRefetchesAndDispatchesExactlyOneWrite() {
    val gate = PhoneAutomationViviLoginFieldWriteGate()

    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.ACTIVATE,
      gate.nextAction(targetPresent = true, targetFocused = false, timedOut = false)
    )
    gate.recordActivation(
      targetAlreadyFocused = false,
      clickAccepted = true,
      focusAccepted = false
    )
    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.WAIT_FOR_REFRESH,
      gate.nextAction(targetPresent = false, targetFocused = false, timedOut = false)
    )
    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.WRITE,
      gate.nextAction(targetPresent = true, targetFocused = true, timedOut = false)
    )
    assertTrue(gate.markWriteDispatched())
    assertFalse(gate.markWriteDispatched())
    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.STOP,
      gate.nextAction(targetPresent = true, targetFocused = true, timedOut = false)
    )
  }

  @Test
  fun rejectedActivationCanBeReacquiredAndTimeoutWritesNothing() {
    val gate = PhoneAutomationViviLoginFieldWriteGate()
    gate.recordActivation(
      targetAlreadyFocused = false,
      clickAccepted = false,
      focusAccepted = false
    )

    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.ACTIVATE,
      gate.nextAction(targetPresent = true, targetFocused = false, timedOut = false)
    )
    assertEquals(
      PhoneAutomationViviLoginFieldWriteAction.STOP,
      gate.nextAction(targetPresent = true, targetFocused = true, timedOut = true)
    )
    assertFalse(gate.writeDispatched)
  }

  @Test
  fun submitWaitsForExactButtonAndDispatchesOneClick() {
    val gate = PhoneAutomationViviLoginSubmitGate()

    assertEquals(
      PhoneAutomationViviLoginSubmitAction.WAIT_FOR_BUTTON,
      gate.nextAction(exactEnabledButtonCount = 0, timedOut = false)
    )
    assertEquals(
      PhoneAutomationViviLoginSubmitAction.WAIT_FOR_BUTTON,
      gate.nextAction(exactEnabledButtonCount = 2, timedOut = false)
    )
    assertEquals(
      PhoneAutomationViviLoginSubmitAction.CLICK,
      gate.nextAction(exactEnabledButtonCount = 1, timedOut = false)
    )
    assertTrue(gate.markClickDispatched())
    assertFalse(gate.markClickDispatched())
    assertEquals(
      PhoneAutomationViviLoginSubmitAction.STOP,
      gate.nextAction(exactEnabledButtonCount = 1, timedOut = false)
    )
  }

  @Test
  fun truncatedTreeWalkIsNeverComplete() {
    val nodeCutoff = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    assertFalse(nodeCutoff.permitNode(visitedNodes = 192, depth = 0, deadlineReached = false))
    assertFalse(nodeCutoff.complete)

    val depthCutoff = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    assertFalse(depthCutoff.permitNode(visitedNodes = 10, depth = 25, deadlineReached = false))
    assertFalse(depthCutoff.complete)

    val childCutoff = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    assertEquals(48, childCutoff.childCountToVisit(49))
    assertFalse(childCutoff.complete)

    val deadlineCutoff = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    assertFalse(deadlineCutoff.permitNode(visitedNodes = 10, depth = 2, deadlineReached = true))
    assertFalse(deadlineCutoff.complete)

    val missingChild = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    missingChild.recordMissingAdvertisedChild()
    assertFalse(missingChild.complete)

    val completeWalk = PhoneAutomationViviLoginTreeWalkGate(192, 24, 48)
    assertTrue(completeWalk.permitNode(visitedNodes = 191, depth = 24, deadlineReached = false))
    assertEquals(48, completeWalk.childCountToVisit(48))
    assertTrue(completeWalk.complete)
  }
}
