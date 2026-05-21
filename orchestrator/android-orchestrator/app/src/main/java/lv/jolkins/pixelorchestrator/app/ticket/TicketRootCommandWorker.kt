package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import kotlin.time.Duration

class TicketRootCommandWorker : RootExecutor, AutoCloseable {
  private val mutex = Mutex()
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private var reader: BufferedReader? = null

  override suspend fun isRootAvailable(): Boolean {
    return try {
      val result = run("id -u")
      result.ok && result.stdout.trim().lineSequence().lastOrNull() == "0"
    } catch (_: Throwable) {
      false
    }
  }

  override suspend fun run(command: String, timeout: Duration): RootResult {
    return runScript(command, timeout)
  }

  override suspend fun runScript(script: String, timeout: Duration): RootResult {
    return withContext(Dispatchers.IO) {
      val start = System.currentTimeMillis()
      try {
        withTimeout(timeout) {
          mutex.withLock {
            executeLocked(script, start, timeout)
          }
        }
      } catch (timeoutError: TimeoutCancellationException) {
        restartLocked()
        rootCommandTimedOut(script, start, timeout)
      } catch (error: Throwable) {
        restartLocked()
        RootResult(
          exitCode = 1,
          stdout = "",
          stderr = error.message ?: error::class.java.simpleName,
          command = script,
          durationMs = System.currentTimeMillis() - start
        )
      }
    }
  }

  private fun executeLocked(script: String, start: Long, timeout: Duration): RootResult {
    val shell = ensureShellLocked()
    val out = writer ?: error("root writer unavailable")
    val input = reader ?: error("root reader unavailable")
    val id = UUID.randomUUID().toString().replace("-", "")
    val startMarker = "__ticket_root_start_$id"
    val endMarker = "__ticket_root_end_$id"
    val deadlineMillis = start + timeout.inWholeMilliseconds.coerceAtLeast(1L)
    out.write("printf '\\n$startMarker\\n'\n")
    out.write("(\n")
    out.write(script)
    out.write("\n) 2>&1\n")
    out.write("ticket_rc=$?\n")
    out.write("printf '\\n$endMarker:%s\\n' \"\$ticket_rc\"\n")
    out.flush()

    val output = StringBuilder()
    val line = StringBuilder()
    var started = false
    var exitCode = 1
    while (true) {
      if (input.ready()) {
        val next = input.read()
        if (next < 0) {
          break
        }
        val char = next.toChar()
        if (char == '\n') {
          val currentLine = line.toString().trimEnd('\r')
          line.setLength(0)
          when {
            !started && currentLine == startMarker -> started = true
            started && currentLine.startsWith("$endMarker:") -> {
              exitCode = currentLine.substringAfter(':').trim().toIntOrNull() ?: 1
              break
            }
            started -> {
              output.append(currentLine).append('\n')
            }
          }
        } else {
          line.append(char)
        }
        continue
      }
      if (!shell.isAlive) {
        break
      }
      if (System.currentTimeMillis() >= deadlineMillis) {
        restartLocked()
        return rootCommandTimedOut(script, start, timeout)
      }
      Thread.sleep(ROOT_READ_POLL_MILLIS)
    }
    if (!shell.isAlive) {
      restartLocked()
    }
    return RootResult(
      exitCode = exitCode,
      stdout = output.toString(),
      stderr = "",
      command = script,
      durationMs = System.currentTimeMillis() - start
    )
  }

  private fun rootCommandTimedOut(script: String, start: Long, timeout: Duration): RootResult {
    return RootResult(
      exitCode = 124,
      stdout = "",
      stderr = "root command timed out after $timeout",
      command = script,
      durationMs = System.currentTimeMillis() - start
    )
  }

  private fun ensureShellLocked(): Process {
    val existing = process
    if (existing != null && existing.isAlive && writer != null && reader != null) {
      return existing
    }
    restartLocked()
    val fresh = ProcessBuilder("su")
      .redirectErrorStream(true)
      .start()
    process = fresh
    writer = BufferedWriter(OutputStreamWriter(fresh.outputStream, Charsets.UTF_8))
    reader = BufferedReader(InputStreamReader(fresh.inputStream, Charsets.UTF_8))
    return fresh
  }

  private fun restartLocked() {
    runCatching { writer?.close() }
    runCatching { reader?.close() }
    runCatching { process?.destroy() }
    runCatching { process?.destroyForcibly() }
    writer = null
    reader = null
    process = null
  }

  private companion object {
    private const val ROOT_READ_POLL_MILLIS = 5L
  }

  override fun close() {
    restartLocked()
  }
}
