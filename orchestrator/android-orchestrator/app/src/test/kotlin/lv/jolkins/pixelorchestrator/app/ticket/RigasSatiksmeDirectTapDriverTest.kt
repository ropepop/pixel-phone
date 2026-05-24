package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RigasSatiksmeDirectTapDriverTest {
  @Test
  fun postSubmitReturnToRegisterScreenIsARejectedCode() = runTest {
    val gateway = FakeDirectTapGateway(
      snapshots = ArrayDeque(
        listOf(
          nodes("REGISTER A TRIP", "1 month"),
          nodes("REGISTER A TRIP", "1 month"),
          nodes("REGISTER A TRIP", "1 month")
        )
      )
    )

    val result = RigasSatiksmeDirectTapDriver(gateway).run("58011")

    assertFalse(result.ok)
    assertEquals("code_rejected_by_rs", result.reason)
    assertFalse(gateway.snapshotReasons.contains("rs_direct_proof_unknown_recheck"))
  }

  private class FakeDirectTapGateway(
    private val snapshots: ArrayDeque<List<PhoneAutomationVisibleNode>>
  ) : RigasSatiksmeDirectTapGateway {
    val snapshotReasons = mutableListOf<String>()

    override suspend fun launchApp(): Boolean = true

    override suspend fun waitForForeground(): Boolean = true

    override suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode> {
      snapshotReasons += reason
      return snapshots.removeFirstOrNull().orEmpty()
    }

    override suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean = true

    override suspend fun enterManualCode(cleanDigits: String, fieldXRatio: Double, fieldYRatio: Double): Boolean = true

    override suspend fun pressBack(reason: String): Boolean = true
  }

  private fun nodes(vararg labels: String): List<PhoneAutomationVisibleNode> {
    return labels.map { label -> PhoneAutomationVisibleNode(text = label, resourceId = "", contentDescription = label) }
  }
}
