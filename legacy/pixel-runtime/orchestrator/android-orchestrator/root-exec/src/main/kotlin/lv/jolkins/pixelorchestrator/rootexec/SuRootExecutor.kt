package lv.jolkins.pixelorchestrator.rootexec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class SuRootExecutor : RootExecutor {

  override suspend fun isRootAvailable(): Boolean {
    return try {
      val result = run("id -u")
      result.ok && result.stdout.trim() == "0"
    } catch (_: Exception) {
      false
    }
  }

  override suspend fun run(command: String, timeout: Duration): RootResult {
    return withContext(Dispatchers.IO) {
      withTimeout(timeout) {
        val start = System.currentTimeMillis()
        val process = ProcessBuilder("su", "-c", command)
          .redirectErrorStream(false)
          .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val end = System.currentTimeMillis()

        RootResult(
          exitCode = exitCode,
          stdout = stdout,
          stderr = stderr,
          command = command,
          durationMs = end - start
        )
      }
    }
  }

  override suspend fun runScript(script: String, timeout: Duration): RootResult {
    val wrapped = buildString {
      append("sh -s <<'EOF'\n")
      append(script)
      append("\nEOF")
    }
    return run(wrapped, timeout)
  }
}
