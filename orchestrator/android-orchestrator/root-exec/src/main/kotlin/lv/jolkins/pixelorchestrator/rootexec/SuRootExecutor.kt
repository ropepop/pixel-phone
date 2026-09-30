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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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

  /** Input readers keep stdin as an app-owned lifeline; finite commands retain run(). */
  fun startInputReader(command: String): Process {
    val stat = File("/proc/self/stat").readText()
    val appPid = stat.substringBefore(' ').toLong()
    val appStart = stat.substringAfterLast(") ").split(' ')[19].toLong()
    val admission = UUID.randomUUID().toString()
    val process = startProcess(inputReaderCommand(command, appPid, appStart), admission)
    val owner = AtomicReference<Pair<Long, Long>?>(null)
    val closed = AtomicBoolean()
    val output = object : InputStream() {
      private var admitted = false

      @Synchronized private fun admit() {
        if (admitted) return
        try {
          val header = ByteArrayOutputStream()
          while (header.size() < 256) {
            val next = process.inputStream.read()
            if (next == '\n'.code || next < 0) break
            header.write(next)
          }
          val fields = header.toString("UTF-8").split(':')
          val pid = fields.getOrNull(1)?.toLongOrNull()
          val ticks = fields.getOrNull(2)?.toLongOrNull()
          check(fields.size == 3 && fields[0] == "root-exec-ready" && pid != null && pid > 1 && ticks != null && ticks > 0) {
            "root command identity unavailable"
          }
          owner.set(pid to ticks)
          if (closed.get()) throw IOException("root input reader is closed")
          process.outputStream.write((admission + "\n").toByteArray())
          process.outputStream.flush()
          admitted = true
        } catch (error: Throwable) {
          if (closed.compareAndSet(false, true)) cleanupTimedOutProcess(process, owner.get())
          throw error
        }
      }

      override fun read(): Int { admit(); return process.inputStream.read() }
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        admit()
        return process.inputStream.read(buffer, offset, length)
      }
      override fun close() { process.inputStream.close() }
    }
    return object : Process() {
      override fun getInputStream(): InputStream = output
      override fun getErrorStream(): InputStream = process.errorStream
      override fun getOutputStream() = process.outputStream
      override fun waitFor(): Int = process.waitFor()
      override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
      override fun exitValue(): Int = process.exitValue()
      override fun isAlive(): Boolean = process.isAlive
      override fun destroy() {
        if (closed.compareAndSet(false, true)) cleanupTimedOutProcess(process, owner.get())
      }
      override fun destroyForcibly(): Process { destroy(); return this }
    }
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
        ${processTreeFunctions()}
        root="$rootPid"
        start_of "${'$'}root"
        [ "${'$'}observed_start" = "$rootStart" ] || exit 0
        kill_tree "${'$'}root" "$rootStart"
      """.trimIndent()

    private fun processTreeFunctions(): String = """
        start_of() {
          observed_start=""
          IFS= read -r line 2>/dev/null < "/proc/${'$'}1/stat" || return 1
          rest="${'$'}{line##*) }"
          set -- ${'$'}rest
          [ "${'$'}1" != Z ] || return 1
          shift 19
          observed_start="${'$'}1"
        }
        children_of() {
          parent="${'$'}1"
          process_rows="${'$'}(ps -A -o PID=,PPID=)" || return 1
          while read -r pid listed_parent extra; do
            [ -n "${'$'}pid${'$'}listed_parent${'$'}extra" ] || continue
            case "${'$'}pid" in ''|*[!0-9]*) return 1 ;; esac
            case "${'$'}listed_parent" in ''|*[!0-9]*) return 1 ;; esac
            [ -z "${'$'}extra" ] || return 1
            [ "${'$'}listed_parent" = "${'$'}parent" ] || continue
            IFS= read -r line 2>/dev/null < "/proc/${'$'}pid/stat" || continue
            rest="${'$'}{line##*) }"
            set -- ${'$'}rest
            [ "${'$'}{2:-}" = "${'$'}parent" ] || continue
            shift 19
            printf '%s:%s\n' "${'$'}pid" "${'$'}1"
          done <<ROOT_EXEC_CHILDREN
        ${'$'}process_rows
        ROOT_EXEC_CHILDREN
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
      """.trimIndent()

    internal fun inputReaderCommand(command: String, appPid: Long, appStart: Long): String = """
      ${processTreeFunctions()}
      start_of "$appPid"
      [ "${'$'}observed_start" = "$appStart" ] || exit 125
      guardian="${'$'}${'$'}"
      start_of "${'$'}guardian" || exit 125
      guardian_start="${'$'}observed_start"
      input_child=""
      input_start=""
      eof_child=""
      eof_start=""
      finish_input() {
        trap - EXIT HUP INT TERM
        [ -z "${'$'}input_start" ] || kill_tree "${'$'}input_child" "${'$'}input_start"
        [ -z "${'$'}eof_start" ] || kill_tree "${'$'}eof_child" "${'$'}eof_start"
        [ -z "${'$'}input_child" ] || wait "${'$'}input_child" 2>/dev/null
        [ -z "${'$'}eof_child" ] || wait "${'$'}eof_child" 2>/dev/null
      }
      trap finish_input EXIT
      trap 'exit 143' HUP INT TERM
      sh -c ${ShellEscaper.singleQuote(command)} < /dev/null &
      input_child="${'$'}!"
      start_of "${'$'}input_child" && input_start="${'$'}observed_start"
      exec 3<&0
      (
        while IFS= read -r unused; do :; done
        start_of "${'$'}guardian"
        [ "${'$'}observed_start" != "${'$'}guardian_start" ] || kill -TERM "${'$'}guardian"
      ) <&3 &
      eof_child="${'$'}!"
      start_of "${'$'}eof_child" && eof_start="${'$'}observed_start"
      exec 3<&-
      wait "${'$'}input_child"
      input_exit="${'$'}?"
      exit "${'$'}input_exit"
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
