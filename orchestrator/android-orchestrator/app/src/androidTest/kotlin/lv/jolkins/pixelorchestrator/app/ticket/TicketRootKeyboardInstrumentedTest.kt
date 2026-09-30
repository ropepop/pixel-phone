package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private const val FIELD_LABEL = "Rust keyboard acceptance fixture"
private const val FINISH_FIXTURE = "finish_keyboard_fixture"

// Exists only in the test APK. No activity or test route enters the installed product.
class KeyboardFixtureActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.getBooleanExtra(FINISH_FIXTURE, false)) {
      finishAndRemoveTask()
      return
    }
    val field = EditText(this).apply {
      contentDescription = FIELD_LABEL
      inputType = InputType.TYPE_CLASS_NUMBER
      setSingleLine(true)
      showSoftInputOnFocus = false
      setText("99999999")
    }
    setContentView(FrameLayout(this).apply {
      addView(field, FrameLayout.LayoutParams(600, 200, Gravity.CENTER))
    })
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.getBooleanExtra(FINISH_FIXTURE, false)) finishAndRemoveTask()
  }
}

@RunWith(AndroidJUnit4::class)
class TicketRootKeyboardInstrumentedTest {
  @Test
  fun packagedHelperReplacesSyntheticDigitsAndStops() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.context
    val target = instrumentation.targetContext
    val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val root = SuRootExecutor()
    assertTrue("Root access is required for the real InputManager journey", root.isRootAvailable())
    val helper = File.createTempFile("rust-keyboard-acceptance-", "", target.filesDir)
    val fixture = Intent().setClassName(context, KeyboardFixtureActivity::class.java.name)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      target.assets.open("ticket-root-keyboard").use { input ->
        helper.outputStream().use { output -> input.copyTo(output) }
      }
      assertTrue("Packaged helper must be executable", helper.setExecutable(true, true))
      context.startActivity(fixture)
      for (digits in listOf("01234567", "89")) {
        val field = awaitField(ui, context.packageName)
        val bounds = Rect().also(field::getBoundsInScreen)
        assertTrue("Fixture field must have real screen geometry", !bounds.isEmpty)
        val path = ShellEscaper.singleQuote(helper.absolutePath)
        val focusedActivity = ShellEscaper.singleQuote("${context.packageName}/${KeyboardFixtureActivity::class.java.name}")
        val result = root.runScript("""
          /system/bin/dumpsys window | /system/bin/grep 'mCurrentFocus=' | /system/bin/grep -F $focusedActivity >/dev/null || exit 60
          printf '%s\n' '$digits' | $path --input-x ${bounds.centerX()} --input-y ${bounds.centerY()} &
          keyboard_pid=${'$'}!
          wait "${'$'}keyboard_pid"
          result=${'$'}?
          kill -0 "${'$'}keyboard_pid" 2>/dev/null && exit 61
          exit "${'$'}result"
        """.trimIndent(), 5.seconds)
        assertEquals("Packaged helper exit: ${result.stderr}", 0, result.exitCode)
        assertEquals("Helper must not emit entered digits", "", result.stdout)
        val deadline = SystemClock.uptimeMillis() + 2000
        var observed = awaitField(ui, context.packageName).text?.toString()
        while (observed != digits && SystemClock.uptimeMillis() < deadline) {
          SystemClock.sleep(20)
          observed = awaitField(ui, context.packageName).text?.toString()
        }
        assertEquals("InputManager must replace the full old value", digits, observed)
      }
    } finally {
      context.startActivity(Intent(fixture).putExtra(FINISH_FIXTURE, true))
      assertTrue("Test helper file must be removed", !helper.exists() || helper.delete())
    }
  }

  private fun awaitField(ui: UiAutomation, packageName: String): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + 3000
    while (SystemClock.uptimeMillis() < deadline) {
      val window = ui.rootInActiveWindow
      if (window?.packageName?.toString() == packageName) {
        val field = window.findAccessibilityNodeInfosByText(FIELD_LABEL).singleOrNull {
          it.contentDescription?.toString() == FIELD_LABEL && it.isEditable && it.isVisibleToUser && it.isEnabled
        }
        if (field != null) return field
      }
      SystemClock.sleep(20)
    }
    throw AssertionError("The dedicated test field is not the active window; no input was dispatched")
  }
}
