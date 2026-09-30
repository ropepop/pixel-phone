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
class TicketRootMediaInstrumentedTest {
  @Test fun rootHelperUsesInstalledNativeByteContractsAndReleasesEachSession() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val product = instrumentation.targetContext.applicationInfo.sourceDir
    val fixture = instrumentation.context.applicationInfo.sourceDir
    val library = ShellEscaper.singleQuote("$product!/lib/arm64-v8a/libpixel_health.so")
    val result = SuRootExecutor().runScript(
      "CLASSPATH=${ShellEscaper.singleQuote("$product:$fixture")} app_process " +
        "-Dpixel.media.native.library=$library /system/bin " +
        "lv.jolkins.pixelorchestrator.app.ticket.TicketRootMediaFixtureMain",
      12.seconds
    )
    assertEquals("Root media bytes and native lifetime: ${result.stderr}", 0, result.exitCode)
    assertTrue(result.stdout.trim().matches(Regex("NATIVE_MEDIA_OK rounds=256 heap_growth_bytes=-?\\d+")))
    instrumentation.sendStatus(0, Bundle().apply { putString("root_media_result", result.stdout.trim()) })
  }
}
