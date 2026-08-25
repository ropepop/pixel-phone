package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTicketStopSecureRestoreTest {
  @Test
  fun stopRestoresBothSavedValuesAndDeletesStateOnlyAfterExactReadback() {
    val fixture = Fixture(savedState = "0\nnull\n", debuggable = "1", secure = "1")

    val result = fixture.run()

    assertEquals(result.output, 0, result.exitCode)
    assertEquals("0", fixture.debuggable())
    assertEquals("null", fixture.secure())
    assertFalse(Files.exists(fixture.stateFile))
  }

  @Test
  fun stopMigratesLegacyOneLineStateBeforeRestoringIt() {
    val fixture = Fixture(savedState = "0\n", debuggable = "1", secure = "1")

    val result = fixture.run()

    assertEquals(result.output, 0, result.exitCode)
    assertEquals("0", fixture.debuggable())
    assertEquals("0", fixture.secure())
    assertFalse(Files.exists(fixture.stateFile))
  }

  @Test
  fun failedSecureRestoreStillAttemptsDebuggableAndRetainsDurableState() {
    val fixture = Fixture(
      savedState = "0\n0\n",
      debuggable = "1",
      secure = "1",
      failSecureMutation = true
    )

    val result = fixture.run()

    assertEquals(result.output, 1, result.exitCode)
    assertEquals("0", fixture.debuggable())
    assertEquals("1", fixture.secure())
    assertTrue(Files.exists(fixture.stateFile))
    assertEquals("0\n0\n", read(fixture.stateFile))
    assertTrue(result.output.contains("saved state was retained"))
  }

  @Test
  fun listenerThatNeverStopsFailsWithoutTouchingTheSavedRecordOrLiveSettings() {
    val fixture = Fixture(
      savedState = "0\n0\n",
      debuggable = "1",
      secure = "1",
      listenerStaysActive = true
    )

    val result = fixture.run()

    assertEquals(result.output, 1, result.exitCode)
    assertEquals("1", fixture.debuggable())
    assertEquals("1", fixture.secure())
    assertTrue(Files.exists(fixture.stateFile))
    assertTrue(result.output.contains("did not stop cleanly"))
  }

  @Test
  fun serviceThatDoesNotQuiesceFailsWithoutRacingTheFallbackRestore() {
    val fixture = Fixture(
      savedState = "0\n0\n",
      debuggable = "1",
      secure = "1",
      serviceStaysActive = true
    )

    val result = fixture.run()

    assertEquals(result.output, 1, result.exitCode)
    assertEquals("1", fixture.debuggable())
    assertEquals("1", fixture.secure())
    assertTrue(Files.exists(fixture.stateFile))
    assertTrue(result.output.contains("service did not quiesce"))
  }

  @Test
  fun missingStateWithLiveBypassNormalizesToSafeValuesAfterServiceQuiesces() {
    val fixture = Fixture(savedState = null, debuggable = "1", secure = "1")

    val result = fixture.run()

    assertEquals(result.output, 0, result.exitCode)
    assertEquals("0", fixture.debuggable())
    assertEquals("0", fixture.secure())
    assertFalse(Files.exists(fixture.stateFile))
  }

  @Test
  fun missingStateAlreadyInactiveIsAReadOnlySuccess() {
    val fixture = Fixture(savedState = null, debuggable = "1", secure = "0")

    val result = fixture.run()

    assertEquals(result.output, 0, result.exitCode)
    assertEquals("1", fixture.debuggable())
    assertEquals("0", fixture.secure())
    assertFalse(Files.exists(fixture.stateFile))
  }

  private class Fixture(
    savedState: String?,
    debuggable: String,
    secure: String,
    failSecureMutation: Boolean = false,
    listenerStaysActive: Boolean = false,
    serviceStaysActive: Boolean = false
  ) {
    private val root: Path = Files.createTempDirectory("ticket-stop-secure-restore")
    private val bin: Path = Files.createDirectories(root.resolve("bin"))
    private val stack: Path = Files.createDirectories(root.resolve("stack"))
    private val debugState: Path = root.resolve("debuggable")
    private val secureState: Path = root.resolve("secure")
    private val lockHelper: Path = root.resolve("lock-helper.sh")
    private val failSecureMutationValue: String
    val stateFile: Path = Files.createDirectories(stack.resolve("apps/ticket-screen/state"))
      .resolve("ro-debuggable-before-ticket")

    init {
      if (savedState != null) {
        Files.write(stateFile, savedState.toByteArray(StandardCharsets.UTF_8))
      }
      Files.write(debugState, debuggable.toByteArray(StandardCharsets.UTF_8))
      Files.write(secureState, secure.toByteArray(StandardCharsets.UTF_8))
      Files.write(lockHelper, "ticket_lock_acquire() { return 0; }\n".toByteArray(StandardCharsets.UTF_8))
      executable("am", "exit 0\n")
      executable(
        "ss",
        if (listenerStaysActive) "printf 'LISTEN 0 8 127.0.0.1:9388 0.0.0.0:*\\n'\n" else "exit 0\n"
      )
      executable("sleep", "exit 0\n")
      executable(
        "dumpsys",
        if (serviceStaysActive) {
          "printf 'ServiceRecord{ticket lv.jolkins.pixelorchestrator/.app.ticket.TicketStreamService}\\n'\n"
        } else {
          "exit 0\n"
        }
      )
      executable(
        "getprop",
        "[ \"${'$'}1\" = ro.debuggable ] || exit 2\ncat \"${'$'}MOCK_DEBUG_STATE\"\n"
      )
      executable(
        "resetprop",
        "[ \"${'$'}1\" = ro.debuggable ] || exit 2\nprintf '%s' \"${'$'}2\" > \"${'$'}MOCK_DEBUG_STATE\"\n"
      )
      executable(
        "settings",
        """
case "${'$'}1:${'$'}2:${'$'}3" in
  get:secure:disable_secure_windows) cat "${'$'}MOCK_SECURE_STATE" ;;
  put:secure:disable_secure_windows)
    [ "${'$'}MOCK_FAIL_SECURE_MUTATION" != 1 ] || exit 9
    printf '%s' "${'$'}4" > "${'$'}MOCK_SECURE_STATE"
    ;;
  delete:secure:disable_secure_windows)
    [ "${'$'}MOCK_FAIL_SECURE_MUTATION" != 1 ] || exit 9
    printf 'null' > "${'$'}MOCK_SECURE_STATE"
    ;;
  *) exit 2 ;;
esac
""".trimIndent() + "\n"
      )
      failSecureMutationValue = if (failSecureMutation) "1" else "0"
    }

    fun run(): Result {
      val process = ProcessBuilder("sh", script().toString())
        .redirectErrorStream(true)
        .apply {
          environment()["PIXEL_STACK_ROOT"] = stack.toString()
          environment()["PIXEL_TICKET_LOCK_HELPER"] = lockHelper.toString()
          environment()["MOCK_DEBUG_STATE"] = debugState.toString()
          environment()["MOCK_SECURE_STATE"] = secureState.toString()
          environment()["MOCK_FAIL_SECURE_MUTATION"] = failSecureMutationValue
          environment()["PATH"] = "${bin}:${environment()["PATH"].orEmpty()}"
        }
        .start()
      val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
      return Result(process.waitFor(), output)
    }

    fun debuggable(): String = read(debugState)

    fun secure(): String = read(secureState)

    private fun executable(name: String, body: String) {
      val path = bin.resolve(name)
      Files.write(path, "#!/bin/sh\n$body".toByteArray(StandardCharsets.UTF_8))
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    private fun script(): Path {
      val relative = "app/src/main/assets/runtime/entrypoints/pixel-ticket-stop.sh"
      return listOf(Path.of(relative), Path.of("../$relative"), Path.of("../../$relative"))
        .firstOrNull(Files::exists) ?: error("Missing $relative")
    }
  }

  private data class Result(val exitCode: Int, val output: String)

  companion object {
    private fun read(path: Path): String =
      String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
