package lv.jolkins.pixelorchestrator.rootexec

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RootCommandCleanupException(cause: Throwable) : IllegalStateException("root command cleanup could not be proved", cause)

class SuRootExecutor internal constructor(
  private val startProcess: (String, String) -> Process,
  private val stopProcessTree: (Long, Long) -> Unit
) : RootExecutor {
  constructor() : this(
    { command, admission -> ProcessBuilder("su", "-c", admittedCommand(command, admission)).redirectErrorStream(false).start() },
    ::killProcessTree
  )

  override suspend fun isRootAvailable(): Boolean {
    return try {
      val result = run("id -u")
      result.ok && result.stdout.trim() == "0"
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (cleanup: RootCommandCleanupException) {
      throw cleanup
    } catch (_: Exception) {
      false
    }
  }

  override suspend fun run(command: String, timeout: Duration): RootResult {
    return withContext(Dispatchers.IO) {
      val start = System.currentTimeMillis()
      var process: Process? = null
      val owner = AtomicReference<Pair<Long, Long>?>(null)
      try {
        val admission = UUID.randomUUID().toString()
        val startedProcess = startProcess(command, admission)
        process = startedProcess

        coroutineScope {
          val ready = CompletableDeferred<Pair<Long, Long>>()
          val stdout = async(Dispatchers.IO) {
            startedProcess.inputStream.bufferedReader().use { reader ->
              val fields = reader.readLine()?.split(':').orEmpty()
              val pid = fields.getOrNull(1)?.toLongOrNull()
              val startTicks = fields.getOrNull(2)?.toLongOrNull()
              check(fields.size == 3 && fields[0] == "root-exec-ready" && pid != null && pid > 1L && startTicks != null && startTicks > 0L) {
                "root command identity unavailable"
              }
              val identity = pid to startTicks
              owner.set(identity)
              ready.complete(identity)
              runCatching { reader.readText() }.getOrDefault("")
            }
          }
          val stderr = async(Dispatchers.IO) { startedProcess.errorStream.readTextSafely() }
          try {
            val finished = withTimeoutOrNull(timeout) {
              ready.await()
              startedProcess.outputStream.write((admission + "\n").toByteArray())
              startedProcess.outputStream.flush()
              runInterruptible { startedProcess.waitFor() }
              true
            } ?: false
            if (!finished) {
              process = null
              cleanupTimedOutProcess(startedProcess, owner.get())
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
          } catch (error: Throwable) {
            // Close the command and its pipes before coroutineScope joins blocked readers.
            val unfinished = process
            process = null
            withContext(NonCancellable) { cleanupTimedOutProcess(unfinished, owner.get()) }
            throw error
          }
        }
      } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { cleanupTimedOutProcess(process, owner.get()) }
        throw cancelled
      } catch (cleanup: RootCommandCleanupException) {
        throw cleanup
      } catch (error: Throwable) {
        cleanupTimedOutProcess(process, owner.get())
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

  private fun cleanupTimedOutProcess(process: Process?, owner: Pair<Long, Long>?) {
    if (process == null) return
    // EOF is the fail-closed stop when readiness has not admitted the command yet.
    runCatching { process.outputStream.close() }
    val stopFailure = if (owner != null) {
      runCatching { stopProcessTree(owner.first, owner.second) }.exceptionOrNull()
    } else null
    runCatching { process.destroy() }
    if (runCatching { process.isAlive }.getOrDefault(false)) {
      runCatching { process.destroyForcibly() }
    }
    val clientStopped = runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    if (stopFailure != null) throw RootCommandCleanupException(stopFailure)
    if (!clientStopped) throw RootCommandCleanupException(IllegalStateException("root client still alive"))
  }

  companion object {
    internal fun admittedCommand(command: String, admission: String): String = """
      IFS= read -r root_stat < /proc/${'$'}${'$'}/stat || exit 125
      root_rest="${'$'}{root_stat##*) }"
      set -- ${'$'}root_rest
      shift 19
      root_start="${'$'}1"
      printf 'root-exec-ready:%s:%s\n' "${'$'}${'$'}" "${'$'}root_start"
      IFS= read -r root_admission || exit 125
      [ "${'$'}root_admission" = ${ShellEscaper.singleQuote(admission)} ] || exit 125
      exec sh -c ${ShellEscaper.singleQuote(command)}
    """.trimIndent()

    internal fun processTreeStopScript(rootPid: Long, rootStart: Long): String = """
        root="$rootPid"
        start_of() {
          observed_start=""
          IFS= read -r line 2>/dev/null < "/proc/${'$'}1/stat" || return 1
          rest="${'$'}{line##*) }"
          set -- ${'$'}rest
          [ "${'$'}1" != Z ] || return 1
          shift 19
          observed_start="${'$'}1"
        }
        start_of "${'$'}root"
        [ "${'$'}observed_start" = "$rootStart" ] || exit 0
        children_of() {
          parent="${'$'}1"
          child_pids="${'$'}(ps -P "${'$'}parent" -o PID=)" || return 1
          for pid in ${'$'}child_pids; do
            IFS= read -r line 2>/dev/null < "/proc/${'$'}pid/stat" || continue
            rest="${'$'}{line##*) }"
            set -- ${'$'}rest
            [ "${'$'}{2:-}" = "${'$'}parent" ] || continue
            shift 19
            printf '%s:%s\n' "${'$'}pid" "${'$'}1"
          done
        }
        kill_tree() {
          local pid="${'$'}1"
          local expected_start="${'$'}2"
          local failed=0
          local children=""
          start_of "${'$'}pid"
          [ "${'$'}observed_start" = "${'$'}expected_start" ] || return 0
          if ! kill -STOP "${'$'}pid" >/dev/null 2>&1; then
            start_of "${'$'}pid"
            [ "${'$'}observed_start" != "${'$'}expected_start" ]
            return ${'$'}?
          fi
          children="${'$'}(children_of "${'$'}pid")" || failed=1
          for child in ${'$'}children; do
            kill_tree "${'$'}{child%%:*}" "${'$'}{child#*:}" || failed=1
          done
          start_of "${'$'}pid"
          [ "${'$'}observed_start" = "${'$'}expected_start" ] || return "${'$'}failed"
          if ! kill -KILL "${'$'}pid" >/dev/null 2>&1; then
            start_of "${'$'}pid"
            [ "${'$'}observed_start" != "${'$'}expected_start" ] || return 1
            return "${'$'}failed"
          fi
          for attempt in 1 2 3 4 5; do
            start_of "${'$'}pid"
            [ "${'$'}observed_start" = "${'$'}expected_start" ] || return "${'$'}failed"
            sleep 0.01
          done
          return 1
        }
        kill_tree "${'$'}root" "$rootStart"
      """.trimIndent()

    private fun killProcessTree(rootPid: Long, rootStart: Long) {
      val killer = ProcessBuilder("su", "-c", processTreeStopScript(rootPid, rootStart))
        .redirectErrorStream(true)
        .start()
      if (!killer.waitFor(1, TimeUnit.SECONDS)) {
        runCatching { killer.destroyForcibly() }
        error("root process tree stop timed out")
      }
      check(killer.exitValue() == 0) { "root process tree stop verification failed" }
    }
  }
}
