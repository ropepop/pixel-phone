package lv.jolkins.pixelorchestrator.supervisor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import org.junit.Test
import kotlin.test.*

class SupervisorStateBridgeTest {
  @Test fun malformedNativeRequestsFailWithoutReturningReplacementStateOrEchoingInput() {
    val state = Json { encodeDefaults = true }.encodeToString(StackStateV1())
    for ((body, change) in listOf(
      "private-test-value" to "{}",
      "{}" to "{}",
      state to "{",
      state to "{\"kind\":\"private-test-value\"}",
      state to "{\"kind\":\"component\",\"name\":\"x\",\"status\":\"private-test-value\",\"failure\":\"\",\"countAsRestart\":true}"
    )) {
      val error = assertFailsWith<IllegalStateException> { NativeSupervisor.transition(body, change, 1) }
      assertFalse(error.message.orEmpty().contains("private-test-value"))
    }
    assertFailsWith<IllegalStateException> { NativeSupervisor.network(state, state, "{}", "{}", 1) }
  }
}
