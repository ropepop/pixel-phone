package lv.jolkins.pixelorchestrator.app.ticket

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class TicketRootVisualInstrumentedTest {
  @Test fun installedRootRecognizerPreservesDatesAndInvalidatesProcessIdentity() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val product = instrumentation.targetContext.applicationInfo.sourceDir
    val fixture = instrumentation.context.applicationInfo.sourceDir
    val library = ShellEscaper.singleQuote("$product!/lib/arm64-v8a/libpixel_health.so")
    val pattern = Regex("NATIVE_VISUAL_OK date=([0-9a-f]{24}) detail=(d_[0-9a-f]{28}) (cold_us=\\d+ p95_us=\\d+ cpu_us=\\d+ native_heap_bytes=\\d+)")
    val runs = (1..2).map {
      val result = SuRootExecutor().runScript(
        "CLASSPATH=${ShellEscaper.singleQuote("$product:$fixture")} app_process " +
          "-Dpixel.media.native.library=$library /system/bin " +
          "lv.jolkins.pixelorchestrator.app.ticket.TicketRootVisualFixtureMain",
        20.seconds
      )
      assertEquals("Root native synthetic recognition: ${result.stderr}", 0, result.exitCode)
      requireNotNull(pattern.matchEntire(result.stdout.trim())) { "Root visual fixture result missing" }
    }
    assertEquals("Persisted date salt must survive root VM restart", runs[0].groupValues[1], runs[1].groupValues[1])
    assertNotEquals("Detail identity must invalidate after root VM restart", runs[0].groupValues[2], runs[1].groupValues[2])
    instrumentation.sendStatus(0, Bundle().apply {
      putString("root_visual_result", "date_restart_stable=true detail_restart_changed=true run1=${runs[0].groupValues[3]} run2=${runs[1].groupValues[3]}")
    })
  }
}
