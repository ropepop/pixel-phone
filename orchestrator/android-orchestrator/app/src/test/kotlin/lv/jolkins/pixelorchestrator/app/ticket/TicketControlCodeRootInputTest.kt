package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TicketControlCodeRootInputTest {
  @Test
  fun buildsOneBoundedRootVirtualKeyboardTypeTransaction() {
    val script = TicketControlCodeRootInput.buildTypeScript(
      digits = "5555",
      inputX = 540,
      inputY = 1321
    )

    assertTrue(script.contains("${TicketControlCodeRootInput.HELPER_PATH} --input-x 540 --input-y 1321"))
    assertTrue(script.contains("printf '%s\\n' '5555' |"))
    assertTrue(script.contains("exit 52"))
    assertFalse(script.contains("input text"))
    assertFalse(script.contains("input tap"))
    assertFalse(script.contains("| uinput -"))
    assertFalse(script.contains("UI_SET_KEYBIT"))
    assertFalse(script.contains("ime disable"))
    assertFalse(script.contains("ime enable"))
    assertFalse(script.contains("ime set"))
    assertTrue(script.contains("settings put secure show_ime_with_hard_keyboard 0"))
    assertFalse(script.contains("input tap 797 1389"))
  }

  @Test
  fun canRegisterKeyboardBeforeOpeningPopup() {
    val script = TicketControlCodeRootInput.buildTypeScript(
      digits = "5555",
      inputX = 540,
      inputY = 1241,
      openX = 210,
      openY = 304
    )

    assertTrue(script.contains("--open-x 210 --open-y 304 --input-x 540 --input-y 1241"))
  }

  @Test
  fun keyboardSuppressionDoesNotDisableOrReplaceTheDefaultIme() {
    val script = TicketControlCodeRootInput.buildTypeScript("5555", 540, 1241)
    val suppress = script.indexOf("settings put secure show_ime_with_hard_keyboard 0")
    val helper = script.indexOf(TicketControlCodeRootInput.HELPER_PATH, suppress)

    assertTrue(suppress >= 0)
    assertTrue(suppress < helper)
    assertFalse(script.contains("default_input_method"))
    assertFalse(script.contains("ime "))
  }

  @Test
  fun nativeHelperIsOneShotBoundedAndKeepsDigitsOnStdin() {
    val path = listOf(
      Path.of("app/src/main/cpp/ticket_root_keyboard.c"),
      Path.of("src/main/cpp/ticket_root_keyboard.c")
    ).first { Files.exists(it) }
    val source = String(Files.readAllBytes(path), Charsets.UTF_8)

    assertTrue(source.contains("open(\"/dev/uinput\""))
    assertTrue(source.contains("UI_DEV_CREATE"))
    assertTrue(source.contains("UI_DEV_DESTROY"))
    assertTrue(source.contains("/proc/bus/input/devices"))
    assertTrue(source.contains("REGISTRATION_TIMEOUT_MS 1500"))
    assertTrue(source.contains("POPUP_SETTLE_MS 250"))
    assertTrue(source.contains("FOCUS_SETTLE_MS 300"))
    assertTrue(source.contains("VALUE_SETTLE_MS 100"))
    assertTrue(source.contains("KEYBOARD_LEASE_MS 6000"))
    assertEquals(6_000L, TicketControlCodeRootInput.KEYBOARD_LEASE_MILLIS)
    assertTrue(source.contains("HELPER_DEADLINE_MS 2700"))
    assertTrue(source.contains("handoff_keyboard_lease()"))
    assertTrue(source.contains("ticket-kbd-lease"))
    assertTrue(source.contains("close(STDOUT_FILENO)"))
    assertTrue(source.contains("secure_zero(digits, sizeof(digits))"))
    assertTrue(source.contains("secure_zero(events, sizeof(events))"))
    assertTrue(source.contains("waitpid(child, &status, WNOHANG)"))
    assertTrue(source.contains("kill(child, SIGKILL)"))
    assertFalse(source.contains("memset(digits, 0"))
    assertTrue(source.contains("read(STDIN_FILENO"))
    assertTrue(source.contains("MAX_DIGITS 8"))
    assertFalse(source.contains("printf(\"%s\", digits"))
    assertFalse(source.contains("system("))
    assertEquals(1, Regex("""run_input_tap\(open_x, open_y""").findAll(source).count())
    assertTrue(source.contains("read_soft_keyboard_visibility(helper_deadline_millis)"))
    assertTrue(source.contains("mInputShown=true"))
    assertTrue(source.contains("mImeWindowVis=0"))
    assertTrue(source.contains("ime_visibility == IME_VISIBILITY_VISIBLE"))
    assertTrue(source.contains("hide_soft_keyboard(helper_deadline_millis)"))
    assertFalse("typing must never implicitly advance or submit the form", source.contains("KEY_TAB"))
    assertFalse("typing must never implicitly submit the form", source.contains("KEY_ENTER"))
    assertFalse("typing must never implicitly submit the form", source.contains("KEY_KPENTER"))
  }

  @Test
  fun retryWaitsForTheInitialKeyboardLeaseAndSafetyMargin() {
    assertEquals(
      4_250L,
      TicketControlCodeRootInput.remainingInitialKeyboardLeaseMillis(
        initialTypeCompletedAtMillis = 1_000L,
        nowMillis = 3_000L,
        safetyMarginMillis = 250L
      )
    )
    assertEquals(
      0L,
      TicketControlCodeRootInput.remainingInitialKeyboardLeaseMillis(
        initialTypeCompletedAtMillis = 1_000L,
        nowMillis = 7_250L,
        safetyMarginMillis = 250L
      )
    )
  }

  @Test
  fun rejectsAnythingOtherThanTwoToEightDigits() {
    listOf("", "1", "123456789", "12 34", "12a4", "-123").forEach { value ->
      try {
        TicketControlCodeRootInput.buildTypeScript(value, 1, 2)
        fail("expected invalid control code to be rejected")
      } catch (_: IllegalArgumentException) {
        // Expected.
      }
    }
  }

  @Test
  fun generatedScriptsPassPosixShellSyntaxCheck() {
    listOf(
      TicketControlCodeRootInput.buildTypeScript("5555", 540, 1239)
    ).forEach { script ->
      val process = ProcessBuilder("sh", "-n").start()
      process.outputStream.bufferedWriter().use { writer -> writer.write(script) }
      assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
    }
  }

}
