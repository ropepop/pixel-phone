package lv.jolkins.pixelorchestrator.app.ticket

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class TicketRootNativeLoadInstrumentedTest {
  @Test fun existingRootAppProcessLoadsExactLibraryInsideInstalledApk() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val product = instrumentation.targetContext.applicationInfo.sourceDir
    val fixture = instrumentation.context.applicationInfo.sourceDir
    val hash = ZipFile(product).use { apk ->
      apk.getInputStream(apk.getEntry("lib/arm64-v8a/libpixel_health.so")).use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
      }
    }
    val result = SuRootExecutor().runScript(
      "CLASSPATH=${ShellEscaper.singleQuote("$fixture:$product")} app_process /system/bin " +
        "lv.jolkins.pixelorchestrator.app.ticket.TicketRootNativeLoadMain ${ShellEscaper.singleQuote(product)}",
      8.seconds
    )
    assertEquals("Root packaged native load: ${result.stderr}", 0, result.exitCode)
    assertEquals("NATIVE_APK_LOAD_OK sha256=$hash", result.stdout.trim())
    instrumentation.sendStatus(0, Bundle().apply { putString("root_native_apk_sha256", hash) })
  }
}
