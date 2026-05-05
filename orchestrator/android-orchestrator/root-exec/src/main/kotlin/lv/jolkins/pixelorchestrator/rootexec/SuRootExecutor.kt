package lv.jolkins.pixelorchestrator.rootexec

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
      val start = System.currentTimeMillis()
      var process: Process? = null
      try {
        val startedProcess = ProcessBuilder("su", "-c", command)
          .redirectErrorStream(false)
          .start()
        process = startedProcess

        coroutineScope {
          val stdout = async(Dispatchers.IO) { startedProcess.inputStream.readTextSafely() }
          val stderr = async(Dispatchers.IO) { startedProcess.errorStream.readTextSafely() }
          val finished = startedProcess.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
          if (!finished) {
            cleanupTimedOutProcess(startedProcess)
            val stdoutText = withTimeoutOrNull(500.milliseconds) { stdout.await() }.orEmpty()
            val stderrText = withTimeoutOrNull(500.milliseconds) { stderr.await() }.orEmpty()
            stdout.cancel()
            stderr.cancel()
            RootResult(
              exitCode = 124,
              stdout = stdoutText,
              stderr = buildString {
                append(stderrText)
                if (isNotBlank()) append('\n')
                append("root command timed out after $timeout")
              },
              command = command,
              durationMs = System.currentTimeMillis() - start
            )
          } else {
            val exitCode = startedProcess.exitValue()
            RootResult(
              exitCode = exitCode,
              stdout = stdout.await(),
              stderr = stderr.await(),
              command = command,
              durationMs = System.currentTimeMillis() - start
            )
          }
        }
      } catch (timeout: TimeoutCancellationException) {
        cleanupTimedOutProcess(process)
        RootResult(
          exitCode = 124,
          stdout = "",
          stderr = "root command timed out after $timeout",
          command = command,
          durationMs = System.currentTimeMillis() - start
        )
      } catch (error: Throwable) {
        cleanupTimedOutProcess(process)
        val end = System.currentTimeMillis()
        RootResult(
          exitCode = 1,
          stdout = "",
          stderr = error.message ?: error::class.java.simpleName,
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

  private fun InputStream.readTextSafely(): String {
    return runCatching {
      bufferedReader().use { it.readText() }
    }.getOrDefault("")
  }

  private fun cleanupTimedOutProcess(process: Process?) {
    if (process == null) return
    val pid = runCatching { process.pid() }.getOrNull()
    if (pid != null) {
      runCatching { killProcessTree(pid) }
    }
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.destroy() }
    if (runCatching { process.isAlive }.getOrDefault(false)) {
      runCatching { process.destroyForcibly() }
    }
  }

  private fun killProcessTree(rootPid: Long) {
    val script = """
      root="$rootPid"
      children_of() {
        parent="${'$'}1"
        for stat in /proc/[0-9]*/stat; do
          [ -r "${'$'}stat" ] || continue
          pid="${'$'}{stat#/proc/}"
          pid="${'$'}{pid%/stat}"
          line="${'$'}(cat "${'$'}stat" 2>/dev/null)" || continue
          rest="${'$'}{line#*) }"
          set -- ${'$'}rest
          [ "${'$'}{2:-}" = "${'$'}parent" ] && echo "${'$'}pid"
        done
      }
      kill_tree() {
        pid="${'$'}1"
        for child in ${'$'}(children_of "${'$'}pid"); do
          kill_tree "${'$'}child"
        done
        kill -TERM "${'$'}pid" >/dev/null 2>&1 || true
      }
      kill_tree "${'$'}root"
      sleep 0.2
      kill -KILL "${'$'}root" >/dev/null 2>&1 || true
    """.trimIndent()
    val killer = ProcessBuilder("su", "-c", script)
      .redirectErrorStream(true)
      .start()
    if (!killer.waitFor(1, TimeUnit.SECONDS)) {
      runCatching { killer.destroyForcibly() }
    }
  }
}
