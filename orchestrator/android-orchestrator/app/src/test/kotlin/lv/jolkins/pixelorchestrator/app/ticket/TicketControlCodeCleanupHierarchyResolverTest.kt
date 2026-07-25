package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketControlCodeCleanupHierarchyResolverTest {
  @Test
  fun blankFirstReadRetriesAndReturnsTheVisiblePopupHierarchy() = runTest {
    val popupHierarchy = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" bounds="[0,0][1080,2424]">
          <node package="com.pv.vivi" class="android.view.View" bounds="[173,1033][908,1480]">
            <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" focusable="true" focused="false" bounds="[236,1096][845,1149]" />
            <node package="com.pv.vivi" class="android.widget.EditText" clickable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[251,1175][829,1301]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" focusable="true" focused="false" bounds="[464,1327][687,1453]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" enabled="true" focusable="true" focused="false" bounds="[713,1327][881,1453]" />
          </node>
        </node>
      </hierarchy>
    """.trimIndent()
    var reads = 0
    var waits = 0

    val resolved = TicketControlCodeCleanupHierarchyResolver.resolve(
      initialHierarchy = "",
      maxFreshReads = 2,
      isUsable = {
        TicketViviPageEnforcer.classifyForRecovery(it) ==
          TicketViviRecoveryState.CONTROL_CODE_POPUP
      },
      readFresh = {
        reads += 1
        if (reads == 1) null else popupHierarchy
      },
      waitBeforeRetry = { waits += 1 }
    )

    assertEquals(popupHierarchy, resolved)
    assertEquals(2, reads)
    assertEquals(1, waits)
    assertEquals(
      "close_control_code_popup",
      TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(resolved)?.reason
    )
  }
}
