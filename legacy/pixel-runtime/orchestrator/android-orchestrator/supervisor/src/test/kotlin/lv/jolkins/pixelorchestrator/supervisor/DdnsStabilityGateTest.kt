package lv.jolkins.pixelorchestrator.supervisor

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DdnsStabilityGateTest {

  @Test
  fun requiresConsecutiveStableReads() {
    val gate = DdnsStabilityGate(requiredStableReads = 2)

    assertFalse(gate.observe("1.2.3.4"))
    assertTrue(gate.observe("1.2.3.4"))
  }

  @Test
  fun resetsOnValueChange() {
    val gate = DdnsStabilityGate(requiredStableReads = 2)

    assertFalse(gate.observe("1.2.3.4"))
    assertFalse(gate.observe("5.6.7.8"))
    assertTrue(gate.observe("5.6.7.8"))
  }
}
