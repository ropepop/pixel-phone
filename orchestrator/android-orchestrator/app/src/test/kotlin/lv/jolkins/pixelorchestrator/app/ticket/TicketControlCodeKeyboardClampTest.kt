package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeKeyboardClampTest {
  @Test
  fun acquireScriptReadsBeforeClampingAndDoesNotWaitForReadiness() {
    val script = TicketControlCodeKeyboardClamp.buildAcquireScript()

    assertTrue(script.indexOf("settings get secure") >= 0)
    assertTrue(script.indexOf("settings get secure") < script.indexOf("settings put secure"))
    assertTrue(script.contains("ime disable"))
    assertTrue(script.contains("enabled_input_methods"))
    assertTrue(script.contains("default_input_method"))
    assertTrue(script.contains("show_ime_with_hard_keyboard 0"))
    assertTrue(script.contains("restore_previous()"))
    assertTrue(script.contains("trap restore_on_failure EXIT"))
    assertTrue(script.contains("trap - EXIT"))
    assertTrue(script.contains("__APPLIED__"))
    assertFalse(script.contains("sleep"))
    assertFalse(script.contains("wait"))
  }

  @Test
  fun exactPreviousSettingValuesAndAbsentStateAreParsed() {
    assertEquals(
      "0",
      TicketControlCodeKeyboardClamp.parseAcquiredState(
        "previous=0\n__APPLIED__\n"
      ).hardKeyboardSetting
    )
    assertEquals(
      "1",
      TicketControlCodeKeyboardClamp.parseAcquiredState(
        "previous=1\n__APPLIED__\n"
      ).hardKeyboardSetting
    )
    assertEquals(
      null,
      TicketControlCodeKeyboardClamp.parseAcquiredState(
        "previous=__ABSENT__\n__APPLIED__\n"
      ).hardKeyboardSetting
    )
  }

  @Test
  fun restoreUsesPutForExistingValueAndDeleteForAbsentValue() {
    val put = TicketControlCodeKeyboardClamp.buildRestoreScript(
      TicketControlCodeKeyboardClamp.PreviousState(hardKeyboardSetting = "1")
    )
    val delete = TicketControlCodeKeyboardClamp.buildRestoreScript(
      TicketControlCodeKeyboardClamp.PreviousState(hardKeyboardSetting = null)
    )

    assertTrue(put.contains("settings put secure show_ime_with_hard_keyboard '1'"))
    assertFalse(put.contains("settings delete secure show_ime_with_hard_keyboard"))
    assertTrue(delete.contains("settings delete secure show_ime_with_hard_keyboard"))
    assertFalse(delete.contains("settings put secure show_ime_with_hard_keyboard"))
  }

  @Test
  fun parseAndRestoreOwnTheExactImeState() {
    val state = TicketControlCodeKeyboardClamp.parseAcquiredState(
      "previous=1\n" +
        "previous_enabled_input_methods=com.android.inputmethod.latin/.LatinIME\n" +
        "previous_default_input_method=com.android.inputmethod.latin/.LatinIME\n" +
        "__APPLIED__\n"
    )

    assertEquals("1", state.hardKeyboardSetting)
    assertEquals("com.android.inputmethod.latin/.LatinIME", state.enabledInputMethods)
    assertEquals("com.android.inputmethod.latin/.LatinIME", state.defaultInputMethod)

    val restore = TicketControlCodeKeyboardClamp.buildRestoreScript(state)
    assertTrue(restore.contains("ime enable 'com.android.inputmethod.latin/.LatinIME'"))
    assertTrue(restore.contains("ime set 'com.android.inputmethod.latin/.LatinIME'"))
    assertTrue(restore.contains("settings put secure enabled_input_methods"))
    assertTrue(restore.contains("settings put secure default_input_method"))
  }

  @Test
  fun generatedAcquireAndRestoreScriptsPassPosixSyntaxCheck() {
    listOf(
      TicketControlCodeKeyboardClamp.buildAcquireScript(),
      TicketControlCodeKeyboardClamp.buildRestoreScript(
        TicketControlCodeKeyboardClamp.PreviousState(
          hardKeyboardSetting = "1",
          enabledInputMethods = "com.android.inputmethod.latin/.LatinIME",
          defaultInputMethod = "com.android.inputmethod.latin/.LatinIME"
        )
      )
    ).forEach { script ->
      val process = ProcessBuilder("sh", "-n").start()
      process.outputStream.use { it.write(script.toByteArray(StandardCharsets.UTF_8)) }
      val errors = process.errorStream.bufferedReader().readText()
      assertEquals(errors, 0, process.waitFor())
    }
  }
}
