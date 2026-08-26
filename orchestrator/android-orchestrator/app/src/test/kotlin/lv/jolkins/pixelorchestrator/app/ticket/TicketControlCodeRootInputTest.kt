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
    assertFalse(script.contains("settings "))
    assertFalse(script.contains("ime "))
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
  fun wrapperDoesNotMutateAndroidImeOrHardwareKeyboardSettings() {
    val script = TicketControlCodeRootInput.buildTypeScript("5555", 540, 1241)

    assertTrue(script.contains(TicketControlCodeRootInput.HELPER_PATH))
    assertFalse(script.contains("show_ime_with_hard_keyboard"))
    assertFalse(script.contains("default_input_method"))
    assertFalse(script.contains("ime "))
    assertFalse(script.contains("settings "))
  }

  @Test
  fun nativeHelperUsesOneBoundedInputManagerBatchAndKeepsDigitsOnStdin() {
    val path = listOf(
      Path.of("app/src/main/cpp/ticket_root_keyboard.c"),
      Path.of("src/main/cpp/ticket_root_keyboard.c")
    ).first { Files.exists(it) }
    val source = String(Files.readAllBytes(path), Charsets.UTF_8)

    assertFalse(source.contains("/dev/uinput"))
    assertFalse(source.contains("UI_DEV_CREATE"))
    assertFalse(source.contains("UI_DEV_DESTROY"))
    assertFalse(source.contains("/proc/bus/input/devices"))
    assertTrue(source.contains("POPUP_SETTLE_MS 250"))
    assertTrue(source.contains("FOCUS_SETTLE_MS 300"))
    assertTrue(source.contains("VALUE_SETTLE_MS 100"))
    assertTrue(source.contains("HELPER_DEADLINE_MS 2700"))
    assertFalse(source.contains("handoff_keyboard_lease"))
    assertFalse(source.contains("ticket-kbd-lease"))
    assertTrue(source.contains("execv(\"/system/bin/input\", arguments)"))
    assertEquals(1, Regex("""execv\(\"/system/bin/input\", arguments\)""").findAll(source).count())
    assertTrue(source.contains("arguments[argument_count++] = \"KEYCODE_MOVE_END\""))
    assertTrue(source.contains("arguments[argument_count++] = \"KEYCODE_DEL\""))
    assertTrue(source.contains("CLEAR_KEY_COUNT 8"))
    assertTrue(source.contains("KEY_EVENT_DELAY_MS \"20\""))
    assertTrue(source.contains("secure_zero(digits, sizeof(digits))"))
    assertTrue(source.contains("waitpid(child, &status, WNOHANG)"))
    assertTrue(source.contains("kill(child, SIGKILL)"))
    val forkCount = Regex("""fork\(\)""").findAll(source).count()
    assertEquals(3, forkCount)
    assertEquals(
      forkCount,
      Regex(
        """pid_t parent_pid = getpid\(\);\s+pid_t child = fork\(\);"""
      ).findAll(source).count()
    )
    assertEquals(
      forkCount,
      Regex(
        """if \(child == 0\) \{\s+if \(prctl\(PR_SET_PDEATHSIG, SIGKILL\) != 0 \|\| getppid\(\) != parent_pid\)"""
      ).findAll(source).count()
    )
    assertEquals(
      forkCount + 1,
      Regex("""prctl\(PR_SET_PDEATHSIG, SIGKILL\)""").findAll(source).count()
    )
    val mainStartup = source.substringAfter("int main(int argc, char **argv) {")
      .substringBefore("long long started_millis = monotonic_millis();")
    assertTrue(mainStartup.contains("pid_t launching_parent_pid = getppid();"))
    assertTrue(mainStartup.contains("launching_parent_pid <= 1"))
    assertTrue(mainStartup.contains("prctl(PR_SET_PDEATHSIG, SIGKILL) != 0"))
    assertTrue(mainStartup.contains("getppid() != launching_parent_pid"))
    assertTrue(mainStartup.contains("return 55;"))
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
    assertFalse(source.contains("hide_soft_keyboard"))
    assertFalse("typing must never implicitly advance or submit the form", source.contains("KEY_TAB"))
    assertFalse("typing must never implicitly submit the form", source.contains("KEY_ENTER"))
    assertFalse("typing must never implicitly submit the form", source.contains("KEY_KPENTER"))
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
