package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.*
import org.junit.Test

class TicketCaptureSettingsRestoreTest {
  @Test fun realRestoreScriptAttemptsBothWritesAndPreservesRecoveryStateOnFailure() = runBlocking {
    for (failSecure in listOf(true, false)) {
      val directory = Files.createTempDirectory("ticket-restore-fault").toFile()
      try {
        val debug = directory.resolve("debug").apply { writeText("1") }
        val secure = directory.resolve("secure").apply { writeText("1") }
        val saved = directory.resolve("ro-debuggable-before-ticket").apply { writeText("0\nnull\n") }
        val root = object : RootExecutor {
          override suspend fun isRootAvailable() = true
          override suspend fun run(command: String, timeout: Duration) = error("Unexpected command")
          override suspend fun runScript(script: String, timeout: Duration): RootResult {
            val localScript = script.replace("/data/local/pixel-stack/apps/ticket-screen/state", directory.path)
            val fixtures = """
              getprop() { cat '${debug.path}'; }
              settings() {
                case "${'$'}1" in
                  get) cat '${secure.path}' ;;
                  put|delete) $failSecure && return 1; printf null > '${secure.path}' ;;
                esac
              }
              resetprop() { :; }
              su() { printf '%s' "${'$'}{3##* }" > '${debug.path}'; }
              rm() { return 1; }
            """.trimIndent()
            val process = ProcessBuilder("sh", "-c", "$fixtures\n$localScript").start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            return RootResult(process.waitFor(), output, errors, "fixture", 0)
          }
        }
        val result = TicketSecureWindowCaptureBypassOwner(root, root).release("fault")
        assertFalse(result.ok)
        assertTrue(result.cleanupRequired)
        assertTrue(result.debuggableRestored)
        assertEquals(!failSecure, result.secureWindowsRestored)
        assertEquals("0", debug.readText())
        assertTrue(saved.exists())
      } finally { directory.deleteRecursively() }
    }
  }

  @Test fun shellTransactionsPreserveOriginalsAcrossAcquireAndRelease() = runBlocking {
    for (original in listOf(null, "0\nnull\n", "1\n0\n", "1\n")) {
      val directory = Files.createTempDirectory("ticket-settings-test").toFile()
      try {
        val debug = directory.resolve("debug").apply { writeText(if (original == null) "0" else "1") }
        val secure = directory.resolve("secure").apply { writeText(if (original == null) "null" else "1") }
        val saved = directory.resolve("ro-debuggable-before-ticket")
        original?.let(saved::writeText)
        var shellCalls = 0
        val root = object : RootExecutor {
          override suspend fun isRootAvailable() = true
          override suspend fun run(command: String, timeout: Duration) = error("Unexpected command")
          override suspend fun runScript(script: String, timeout: Duration): RootResult {
            shellCalls++
            val localScript = script.replace("/data/local/pixel-stack/apps/ticket-screen/state", directory.path)
            val fixtures = """
              getprop() { cat '${debug.path}'; }
              settings() {
                case "${'$'}1" in
                  get) cat '${secure.path}' ;;
                  put) printf '%s' "${'$'}4" > '${secure.path}' ;;
                  delete) printf null > '${secure.path}' ;;
                  *) return 1 ;;
                esac
              }
              resetprop() { :; }
              su() { printf '%s' "${'$'}{3##* }" > '${debug.path}'; }
            """.trimIndent()
            val process = ProcessBuilder("sh", "-c", "$fixtures\n$localScript").start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            return RootResult(process.waitFor(), output, errors, "fixture", 0)
          }
        }
        val owner = TicketSecureWindowCaptureBypassOwner(root, root)
        val lease = owner.ensureWithResult("test").lease
        assertNotNull(lease)
        assertEquals("1", debug.readText())
        assertEquals("1", secure.readText())
        val expected = original?.trim()?.lines()?.let { it[0] to it.getOrElse(1) { "0" } }
          ?: ("0" to "null")
        assertEquals(listOf(expected.first, expected.second), saved.readLines())
        val beforeReleaseCalls = shellCalls
        assertTrue(owner.releaseAcquiredLease(lease!!, "test")!!.ok)
        assertEquals(2, shellCalls - beforeReleaseCalls)
        assertEquals(expected.first, debug.readText())
        assertEquals(expected.second, secure.readText())
        assertFalse(saved.exists())
      } finally {
        directory.deleteRecursively()
      }
    }
  }

  private class SettingsRoot(
    var saved: Pair<String, String>? = "0" to "null",
    val failSecure: Boolean = false,
    val failClear: Boolean = false
  ) : RootExecutor {
    var debug = "1"
    var secure = "1"
    var debugAttempts = 0
    var clearAttempts = 0
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration) = error("Unexpected command")
    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      var output = ""
      var exit = 0
      when {
        "# ticket_secure_capture_readback" in script -> output = """
          debuggable=$debug
          disable_secure_windows=$secure
          state_file_present=${if (saved == null) 0 else 1}
          saved_debuggable=${saved?.first.orEmpty()}
          saved_disable_secure_windows=${saved?.second.orEmpty()}
        """.trimIndent()
        "# ticket_secure_capture_restore_transaction" in script -> {
          val targetDebug = saved?.first ?: "0"
          val targetSecure = saved?.second ?: "0"
          if (!failSecure) secure = targetSecure
          debugAttempts++
          debug = targetDebug
          if (debug == targetDebug && secure == targetSecure && saved != null) {
            clearAttempts++
            if (!failClear) saved = null
          }
          output = """
            debuggable=$debug
            disable_secure_windows=$secure
            state_file_present=${if (saved == null) 0 else 1}
            saved_debuggable=${saved?.first.orEmpty()}
            saved_disable_secure_windows=${saved?.second.orEmpty()}
          """.trimIndent()
        }
        else -> error("Unexpected script")
      }
      return RootResult(exit, output, "", "fixture", 0)
    }
  }

  @Test fun restorationUsesSavedValuesAndUnownedSafeDefaults() = runBlocking {
    for (saved in listOf("0" to "null", "1" to "0", null)) {
      val root = SettingsRoot(saved)
      val result = TicketSecureWindowCaptureBypassOwner(root, root).release("test")
      assertTrue(result.ok)
      assertEquals(saved?.first ?: "0", root.debug)
      assertEquals(saved?.second ?: "0", root.secure)
      assertNull(root.saved)
      assertEquals(if (saved == null) 0 else 1, root.clearAttempts)
    }
  }

  @Test fun failedFirstRestoreStillRestoresSecondAndKeepsRecoveryState() = runBlocking {
    val root = SettingsRoot(failSecure = true)
    val result = TicketSecureWindowCaptureBypassOwner(root, root).release("test")
    assertFalse(result.ok)
    assertTrue(result.cleanupRequired)
    assertTrue(result.debuggableRestored)
    assertEquals(1, root.debugAttempts)
    assertEquals(0, root.clearAttempts)
    assertNotNull(root.saved)
  }

  @Test fun failedStateDeletionCannotReportCleanRelease() = runBlocking {
    val root = SettingsRoot(failClear = true)
    val result = TicketSecureWindowCaptureBypassOwner(root, root).release("test")
    assertTrue(result.secureWindowsRestored && result.debuggableRestored)
    assertFalse(result.ok)
    assertTrue(result.cleanupRequired)
    assertNotNull(root.saved)
  }
}
