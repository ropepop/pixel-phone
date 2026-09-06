package lv.jolkins.pixelorchestrator.rootexec

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class SuRootExecutorTest {
  @Test
  fun cancellationBeforeReadinessNeverAdmitsTheCommand() = runBlocking {
    val process = BlockedProcess(ready = false)
    val stops = AtomicInteger()
    val executor = SuRootExecutor({ _, _ -> process }, { _, _ -> stops.incrementAndGet() })
    val operation = async { executor.run("not admitted", 30.seconds) }
    withContext(Dispatchers.IO) { assertTrue(process.stdout.readStarted.await(2, TimeUnit.SECONDS)) }

    withTimeout(2.seconds) { operation.cancelAndJoin() }

    assertEquals(0, process.stdin.size())
    assertEquals(0, stops.get())
    assertTrue(!process.isAlive)
  }

  @Test
  fun unprovenStopEscapesCancellationAsFailure() = runBlocking {
    supervisorScope {
      val process = BlockedProcess()
      val executor = SuRootExecutor({ _, _ -> process }, { _, _ -> error("stop verification failed") })
      val operation = async { executor.run("blocked", 30.seconds) }
      withContext(Dispatchers.IO) { assertTrue(process.waitStarted.await(2, TimeUnit.SECONDS)) }

      operation.cancel()

      withTimeout(2.seconds) { assertFailsWith<RootCommandCleanupException> { operation.await() } }
      Unit
    }
  }

  @Test
  fun admissionScriptRequiresCorrectTokenAndPreservesCommandOutput() {
    val stat = Files.createTempFile("root-exec-stat-", ".txt")
    Files.writeString(stat, "42 (shell with ) parentheses) " + (listOf("S") + List(18) { "0" } + "99").joinToString(" ") + "\n")
    try {
      for (token in listOf("wrong", "permit")) {
        val script = SuRootExecutor.admittedCommand("printf 'command output'", "permit")
          .replace("/proc/${'$'}${'$'}/stat", ShellEscaper.singleQuote(stat.toString()))
        val process = ProcessBuilder("sh", "-c", script).start()
        try {
          val reader = process.inputStream.bufferedReader()
          assertTrue(reader.readLine().matches(Regex("root-exec-ready:[0-9]+:99")))
          process.outputStream.write((token + "\n").toByteArray())
          process.outputStream.close()
          assertTrue(process.waitFor(2, TimeUnit.SECONDS))
          assertEquals(if (token == "permit") 0 else 125, process.exitValue())
          assertEquals(if (token == "permit") "command output" else "", reader.readText())
        } finally {
          process.destroyForcibly()
        }
      }
    } finally {
      Files.deleteIfExists(stat)
    }
  }

  @Test
  fun productionRootIdentityDoesNotDependOnJvmOnlyProcessPid() {
    val source = Files.readString(Path.of("src/main/kotlin/lv/jolkins/pixelorchestrator/rootexec/SuRootExecutor.kt"))
    assertTrue(!source.contains(".pid()"))
  }

  @Test
  fun cancellationStopsARealShellWriterBeforeReturning() = runBlocking {
    val output = Files.createTempFile("root-exec-cancel-", ".txt")
    var process: Process? = null
    val executor = SuRootExecutor(
      { command, _ -> startLocalCommand(command).also { process = it } },
      { pid, _ ->
        val owned = ProcessHandle.of(pid).orElseThrow()
        owned.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        owned.destroyForcibly()
      }
    )
    try {
      val operation = async {
        executor.run("while :; do printf x >> ${ShellEscaper.singleQuote(output.toString())}; sleep 0.02; done", 30.seconds)
      }
      withTimeout(2.seconds) { while (Files.size(output) == 0L) delay(10) }

      withTimeout(2.seconds) { operation.cancelAndJoin() }
      assertTrue(process?.waitFor(1, TimeUnit.SECONDS) == true)
      val finalSize = Files.size(output)
      delay(100)
      assertEquals(finalSize, Files.size(output))
    } finally {
      process?.destroyForcibly()
      Files.deleteIfExists(output)
    }
  }

  @Test
  fun cancellationStopsCommandBeforeJoiningItsBlockedOutputReaders() = runBlocking {
    val process = BlockedProcess()
    val stops = AtomicInteger()
    val executor = SuRootExecutor({ _, _ -> process }, { pid, _ ->
      assertEquals(42L, pid)
      stops.incrementAndGet()
      process.destroy()
    })
    val operation = async { executor.run("blocked command", 30.seconds) }
    withContext(Dispatchers.IO) {
      assertTrue(process.waitStarted.await(2, TimeUnit.SECONDS))
      assertTrue(process.stdout.readStarted.await(2, TimeUnit.SECONDS))
      assertTrue(process.stderr.readStarted.await(2, TimeUnit.SECONDS))
    }

    withTimeout(2.seconds) { operation.cancelAndJoin() }

    assertEquals(1, stops.get())
    assertTrue(process.waitInterrupted.get())
    assertTrue(process.stdout.closed.get())
    assertTrue(process.stderr.closed.get())
    assertTrue(!process.isAlive)
  }

  @Test
  fun elapsedCommandDeadlineReturnsTimeoutAndClosesBothPipes() = runBlocking {
    val process = BlockedProcess()
    val executor = SuRootExecutor({ _, _ -> process }, { _, _ -> process.destroy() })

    val result = withTimeout(2.seconds) { executor.run("blocked command", 20.milliseconds) }

    assertEquals(124, result.exitCode)
    assertTrue(result.stderr.contains("root command timed out"))
    assertTrue(process.stdout.closed.get())
    assertTrue(process.stderr.closed.get())
  }

  @Test
  fun cancellationIsNeverConvertedToCommandFailureOrMissingRoot() = runBlocking {
    val executor = SuRootExecutor({ _, _ -> throw CancellationException("cancelled") }, { _, _ -> })

    assertFailsWith<CancellationException> { executor.run("unused", 1.seconds) }
    assertFailsWith<CancellationException> { executor.isRootAvailable() }
    Unit
  }

  @Test
  fun completedCommandPreservesOutputAndExitStatus() = runBlocking {
    val stops = AtomicInteger()
    val executor = SuRootExecutor(
      { command, _ -> startLocalCommand(command) },
      { _, _ -> stops.incrementAndGet() }
    )

    val result = executor.run("printf 'output'; printf 'error' >&2; exit 7", 2.seconds)

    assertEquals(7, result.exitCode)
    assertEquals("output", result.stdout)
    assertEquals("error", result.stderr)
    assertEquals(0, stops.get())
  }

  private fun startLocalCommand(command: String): Process = ProcessBuilder(
    "sh", "-c", "printf 'root-exec-ready:%s:99\\n' \"${'$'}${'$'}\"; read admission; " + command
  ).start()

  private class BlockingPipe(prefix: String = "") : InputStream() {
    private val initial = prefix.toByteArray().iterator()
    val readStarted = CountDownLatch(1)
    val closed = AtomicBoolean()
    private val released = CountDownLatch(1)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (!initial.hasNext()) return super.read(buffer, offset, length)
      var count = 0
      while (count < length && initial.hasNext()) buffer[offset + count++] = initial.nextByte()
      return count
    }

    override fun read(): Int {
      if (initial.hasNext()) return initial.nextByte().toInt() and 255
      readStarted.countDown()
      released.await()
      return -1
    }

    override fun close() {
      closed.set(true)
      released.countDown()
    }
  }

  private class BlockedProcess(ready: Boolean = true) : Process() {
    val stdout = BlockingPipe(if (ready) "root-exec-ready:42:99\n" else "")
    val stderr = BlockingPipe()
    val stdin = ByteArrayOutputStream()
    val waitStarted = CountDownLatch(1)
    val waitInterrupted = AtomicBoolean()
    private val exited = CountDownLatch(1)

    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream() = stdin
    override fun isAlive() = exited.count > 0
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
    override fun waitFor(): Int {
      waitStarted.countDown()
      return try { exited.await(); 0 } catch (interrupted: InterruptedException) {
        waitInterrupted.set(true)
        throw interrupted
      }
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
      waitStarted.countDown()
      return try {
        exited.await(timeout, unit)
      } catch (interrupted: InterruptedException) {
        waitInterrupted.set(true)
        throw interrupted
      }
    }

    override fun destroy() {
      exited.countDown()
      stdout.close()
      stderr.close()
    }
  }
}
