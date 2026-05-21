package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketRootCommandWorkerSourceTest {

  @Test
  fun persistentRootWorkerEnforcesTimeoutsWithoutBlockingReadLine() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCommandWorker.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketRootCommandWorker.kt")
    )

    assertTrue(source.contains("executeLocked(script, start, timeout)"))
    assertTrue(source.contains("deadlineMillis"))
    assertTrue(source.contains("input.ready()"))
    assertTrue(source.contains("input.read()"))
    assertTrue(source.contains("rootCommandTimedOut(script, start, timeout)"))
    assertFalse(source.contains("input.readLine() ?: break"))
  }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}
