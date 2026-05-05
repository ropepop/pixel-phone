package lv.jolkins.pixelorchestrator.rootexec

import kotlin.test.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SuRootExecutorSourceTest {

  @Test
  fun rootCommandsDrainStreamsConcurrentlyAndCleanTimedOutProcessTrees() {
    val source = Files.readString(
      Path.of("src/main/kotlin/lv/jolkins/pixelorchestrator/rootexec/SuRootExecutor.kt")
    )

    assertTrue(source.contains("async(Dispatchers.IO) { startedProcess.inputStream.readTextSafely() }"))
    assertTrue(source.contains("async(Dispatchers.IO) { startedProcess.errorStream.readTextSafely() }"))
    assertTrue(source.contains("startedProcess.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1L), TimeUnit.MILLISECONDS)"))
    assertTrue(source.contains("cleanupTimedOutProcess(startedProcess)"))
    assertTrue(source.contains("private fun killProcessTree(rootPid: Long)"))
    assertTrue(source.contains("children_of()"))
    assertTrue(source.contains("kill -KILL"))
  }
}
