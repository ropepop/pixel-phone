package lv.jolkins.pixelorchestrator.rootexec

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** Disposable ART/Magisk fixture. It never opens input devices or changes phone settings. */
object RootInputReaderLifetimeFixture {
  private const val OUT = "fixture-output\u0000é\n"
  private const val ERR = "fixture-error\n"
  private val root = SuRootExecutor()
  private lateinit var receipts: File

  @JvmStatic fun main(args: Array<String>) {
    try {
      execute(args)
    } catch (error: Throwable) {
      error.printStackTrace(System.err)
      exitProcess(1)
    }
  }

  private fun execute(args: Array<String>) {
    val mode = args[0]
    val directory = File(args[1]).apply { mkdirs() }
    receipts = File(args[2])
    check(receipts.isDirectory) { "owned root-writable receipt directory is missing" }
    if (mode == "hold" || mode == "hold-before") {
      if (mode == "hold-before") {
        val legacy = legacyProcess()
        File(directory, "owner").writeText(File("/proc/self/stat").readText())
        legacy.javaClass.getMethod("waitFor").invoke(legacy)
        return
      }
      val process = root.startInputReader(command(File(receipts, "child")))
      bytes(process)
      File(directory, "owner").writeText(File("/proc/self/stat").readText())
      process.waitFor()
      return
    }
    if (mode == "before") {
      ownerDeath(directory, frozen = true)
      return
    }
    check(mode == "all")
    treeEnumeration()
    normalAndEof()
    forcedClientExit()
    deniedAdmission()
    generationMismatch()
    unrelatedReader()
    ownerDeath(directory)
    completedBytes()
    println("PASS input-reader lifetime: normal cancel, EOF, client exit, admission denial, generation fence, unrelated preservation, owner death, exact bytes/exit")
  }

  private fun command(receipt: File, readParent: Boolean = true): String = """
    IFS= read -r own_stat < /proc/${'$'}${'$'}/stat || {
      printf 'fixture could not read own stat\n' >&2
      exit 125
    }
    own_rest="${'$'}{own_stat##*) }"
    set -- ${'$'}own_rest
    own_parent="${'$'}2"
    shift 19
    own_start="${'$'}1"
    ${if (readParent) """
      IFS= read -r parent_stat < "/proc/${'$'}own_parent/stat" || {
        printf 'fixture could not read parent stat: %s\n' "${'$'}own_parent" >&2
        exit 125
      }
      parent_rest="${'$'}{parent_stat##*) }"
      set -- ${'$'}parent_rest
      shift 19
    """.trimIndent() else "own_parent=0\nset -- 0"}
    printf '%s:%s:%s:%s\n' "${'$'}${'$'}" "${'$'}own_start" "${'$'}own_parent" "${'$'}1" > ${ShellEscaper.singleQuote(receipt.path)} || {
      printf 'fixture receipt publication failed\n' >&2
      exit 125
    }
    chmod 0644 ${ShellEscaper.singleQuote(receipt.path)} || {
      printf 'fixture receipt permission failed\n' >&2
      exit 125
    }
    printf 'fixture-output\000é\n'
    printf 'fixture-error\n' >&2
    exec sleep 300
  """.trimIndent()

  private fun bytes(process: Process) {
    check(process.inputStream.readNBytes(OUT.toByteArray().size).contentEquals(OUT.toByteArray())) { "stdout bytes changed" }
    check(process.errorStream.readNBytes(ERR.toByteArray().size).contentEquals(ERR.toByteArray())) { "stderr bytes changed" }
  }

  private fun identities(receipt: File): List<Pair<Long, Long>> {
    val fields = receipt.readText().trim().split(':').map(String::toLong)
    check(fields.size == 4)
    return listOf(fields[0] to fields[1], fields[2] to fields[3])
  }

  private fun live(identity: Pair<Long, Long>): Boolean {
    val result = run("""
      if ! IFS= read -r stat < /proc/${identity.first}/stat 2>/dev/null; then
        [ ! -e /proc/${identity.first}/stat ] && exit 3
        exit 125
      fi
      rest="${'$'}{stat##*) }"
      set -- ${'$'}rest
      [ "${'$'}1" != Z ] || exit 3
      shift 19
      [ "${'$'}1" = '${identity.second}' ] || exit 3
    """.trimIndent())
    check(result.exitCode == 0 || result.exitCode == 3) { "root liveness probe failed: ${describe(result)}" }
    return result.exitCode == 0
  }

  private fun quiet(identities: List<Pair<Long, Long>>) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (identities.any(::live)) {
      check(System.nanoTime() < until) { "owned process survived its lifetime" }
      Thread.sleep(20)
    }
  }

  private fun cleanup(identities: List<Pair<Long, Long>>) {
    for ((pid, ticks) in identities) {
      val result = run(SuRootExecutor.processTreeStopScript(pid, ticks))
      check(result.ok) { "fixture cleanup unproved for $pid:$ticks: ${describe(result)}" }
    }
  }

  private fun run(command: String): RootResult = runBlocking { root.run(command, 5.seconds) }

  private fun describe(result: RootResult): String =
    "exit=${result.exitCode}; durationMs=${result.durationMs}; stdout=${result.stdout.take(8192)}; stderr=${result.stderr.take(8192)}"

  private fun treeEnumeration() {
    for (mode in listOf("frozen", "current", "missing")) {
      val receipt = File(receipts, "tree-$mode")
      val legacy = legacyProcess(receipt)
      val client = legacy.javaClass.getDeclaredField("process").apply { isAccessible = true }.get(legacy) as Process
      val owner = identities(receipt).first()
      try {
        val current = SuRootExecutor.processTreeStopScript(owner.first, owner.second)
        val script = when (mode) {
          "frozen" -> {
            val type = Class.forName("legacy.inputreader.FrozenRootTree")
            val method = type.methods.single { it.name.startsWith("processTreeStopScript") }
            method.invoke(type.getField("INSTANCE").get(null), owner.first, owner.second) as String
          }
          "missing" -> {
            val missing = File(receipts, "missing-process-enumerator")
            check(!missing.exists())
            current.replace("ps -A -o PID=,PPID=", "${ShellEscaper.singleQuote(missing.path)} -A -o PID=,PPID=")
          }
          else -> current
        }
        val result = run(script)
        check(result.exitCode == if (mode == "current") 0 else 1) {
          "unexpected $mode enumeration result: ${describe(result)}"
        }
        quiet(listOf(owner))
        check(client.waitFor(3, TimeUnit.SECONDS)) { "owned legacy client did not exit" }
        println("PASS actual $mode enumeration for ${owner.first}:${owner.second}: ${describe(result)}")
      } finally {
        cleanup(listOf(owner))
      }
    }
  }

  private fun normalAndEof() {
    for (eof in listOf(false, true)) {
      val receipt = File(receipts, if (eof) "eof" else "normal")
      val process = root.startInputReader(command(receipt))
      var owners = emptyList<Pair<Long, Long>>()
      try {
        bytes(process)
        owners = identities(receipt)
        if (eof) process.outputStream.close() else process.destroy()
        check(process.waitFor(3, TimeUnit.SECONDS))
        quiet(owners)
      } finally {
        process.destroyForcibly()
        cleanup(owners)
      }
      println("PASS ${if (eof) "writer EOF" else "normal cancel"}")
    }
  }

  private fun forcedClientExit() {
    var client: Process? = null
    val executor = SuRootExecutor({ command, admission ->
      ProcessBuilder("su", "-c", SuRootExecutor.admittedCommand(command, admission)).start().also { client = it }
    }, { pid, ticks -> check(run(SuRootExecutor.processTreeStopScript(pid, ticks)).ok) })
    val receipt = File(receipts, "client-exit")
    val process = executor.startInputReader(command(receipt))
    var owners = emptyList<Pair<Long, Long>>()
    try {
      bytes(process)
      owners = identities(receipt)
      client!!.destroyForcibly()
      check(client!!.waitFor(3, TimeUnit.SECONDS))
      quiet(owners)
    } finally {
      process.destroyForcibly()
      cleanup(owners)
    }
    println("PASS independent client exit")
  }

  private fun deniedAdmission() {
    val closedReceipt = File(receipts, "closed-before-admission")
    val unadmitted = root.startInputReader(command(closedReceipt))
    try {
      unadmitted.destroy()
      check(unadmitted.waitFor(3, TimeUnit.SECONDS) && !closedReceipt.exists())
    } finally { unadmitted.destroyForcibly() }
    for (malformed in listOf(false, true)) {
      val receipt = File(receipts, "denied-$malformed")
      val executor = SuRootExecutor({ command, admission ->
        var shell = SuRootExecutor.admittedCommand(command, if (malformed) admission else "wrong-token")
        if (malformed) shell = shell.replace("root-exec-ready", "invalid-ready")
        ProcessBuilder("su", "-c", shell).start()
      }, { pid, ticks -> check(run(SuRootExecutor.processTreeStopScript(pid, ticks)).ok) })
      val process = executor.startInputReader(command(receipt))
      try {
        val output = runCatching { process.inputStream.readBytes() }
        if (malformed) check(output.isFailure) else check(output.getOrThrow().isEmpty())
        check(process.waitFor(3, TimeUnit.SECONDS))
        check(!receipt.exists()) { "denied startup admitted a child" }
      } finally { process.destroyForcibly() }
    }
    println("PASS readiness/admission denials")
  }

  private fun generationMismatch() {
    val stat = File("/proc/self/stat").readText()
    val pid = stat.substringBefore(' ').toLong()
    val ticks = stat.substringAfterLast(") ").split(' ')[19].toLong()
    val receipt = File(receipts, "wrong-generation")
    val result = run(SuRootExecutor.inputReaderCommand(command(receipt), pid, ticks + 1))
    check(result.exitCode == 125 && !receipt.exists())
    println("PASS owner generation mismatch")
  }

  private fun unrelatedReader() {
    val firstFile = File(receipts, "first")
    val secondFile = File(receipts, "second")
    val first = root.startInputReader(command(firstFile))
    val second = root.startInputReader(command(secondFile))
    var owners = emptyList<Pair<Long, Long>>()
    try {
      bytes(first); bytes(second)
      val firstOwners = identities(firstFile)
      val secondOwners = identities(secondFile)
      owners = firstOwners + secondOwners
      first.destroy()
      quiet(firstOwners)
      check(secondOwners.all(::live)) { "unrelated reader was stopped" }
      val stale = secondOwners.first()
      check(run(SuRootExecutor.processTreeStopScript(stale.first, stale.second + 1)).ok)
      check(secondOwners.all(::live)) { "wrong generation stopped a replacement" }
    } finally {
      first.destroyForcibly(); second.destroyForcibly(); cleanup(owners)
    }
    println("PASS unrelated reader and stale identity preserved")
  }

  private fun ownerDeath(directory: File, frozen: Boolean = false) {
    val holder = File(directory, "holder").apply { mkdirs() }
    val process = ProcessBuilder("app_process", "/system/bin", javaClass.name, if (frozen) "hold-before" else "hold", holder.path, receipts.path).start()
    var owners = emptyList<Pair<Long, Long>>()
    var primary: Throwable? = null
    try {
      val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      val ready = File(holder, "owner")
      while (!ready.exists()) {
        if (!process.isAlive) {
          val stdout = process.inputStream.readNBytes(8192).toString(Charsets.UTF_8)
          val stderr = process.errorStream.readNBytes(8192).toString(Charsets.UTF_8)
          error("fixture owner exited before ready: exit=${process.exitValue()}; stdout=$stdout; stderr=$stderr")
        }
        check(System.nanoTime() < until) { "fixture owner did not become ready" }
        Thread.sleep(20)
      }
      owners = identities(File(receipts, "child")).let { if (frozen) it.take(1) else it }
      process.destroyForcibly()
      check(process.waitFor(3, TimeUnit.SECONDS))
      quiet(owners)
    } catch (error: Throwable) {
      primary = error
      throw error
    } finally {
      try {
        process.destroyForcibly()
        if (owners.isEmpty() && File(receipts, "child").exists()) {
          owners = identities(File(receipts, "child")).let { if (frozen) it.take(1) else it }
        }
        cleanup(owners)
      } catch (cleanup: Throwable) {
        if (primary == null) throw cleanup
        primary.addSuppressed(cleanup)
      }
    }
    println("PASS actual fixture-owner death")
  }

  private fun completedBytes() {
    val process = root.startInputReader("printf 'unchanged'; printf 'error' >&2; exit 7")
    try {
      check(process.inputStream.readBytes().toString(Charsets.UTF_8) == "unchanged")
      check(process.errorStream.readBytes().toString(Charsets.UTF_8) == "error")
      check(process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 7)
    } finally { process.destroyForcibly() }
    println("PASS completed stdout/stderr/exit")
  }

  private fun legacyProcess(receipt: File = File(receipts, "child")): Any {
    // The build recipe extracts this unchanged factory/wrapper from the frozen checkout.
    val factory = Class.forName("legacy.inputreader.DefaultRootTouchProcessFactory")
    val instance = factory.getField("INSTANCE").get(null)
    val process = factory.getMethod("start", String::class.java).invoke(instance, command(receipt, readParent = false))
    val type = process.javaClass
    val client = type.getDeclaredField("process").apply { isAccessible = true }.get(process) as Process
    fun call(name: String): Any? = type.getMethod(name).invoke(process)
    fun read(reader: BufferedReader, text: String, stream: String) {
      val chars = CharArray(text.length)
      var offset = 0
      while (offset < chars.size) {
        val count = reader.read(chars, offset, chars.size - offset)
        if (count <= 0) {
          val exited = client.waitFor(1, TimeUnit.SECONDS)
          val stderr = if (exited) client.errorStream.readNBytes(8192).toString(Charsets.UTF_8) else "client still alive"
          error("frozen reader $stream ended before fixture bytes; exit=${if (exited) client.exitValue() else "running"}; stderr=$stderr")
        }
        offset += count
      }
      check(chars.concatToString() == text) {
        "frozen reader $stream characters changed; expected=${text.map { it.code.toString(16) }}; received=${chars.map { it.code.toString(16) }}; decoder=UTF-8"
      }
    }
    read(call("getStdout") as BufferedReader, OUT, "stdout")
    read(call("getStderr") as BufferedReader, ERR, "stderr")
    return process
  }
}
