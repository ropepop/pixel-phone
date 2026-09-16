package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketPanelCleanupScriptTest {
  @Test
  fun generatedProtectionProgramsParseIncludingDetachedChild() {
    val launch = TicketActionPanelDarkLease.panelDarkLaunchScript(123, "syntax-test")
    val childStart = launch.indexOf("nohup sh -c '") + "nohup sh -c '".length
    val childEnd = launch.indexOf("' ${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}", childStart)
    assertTrue(childStart > 0 && childEnd > childStart)
    for (program in listOf(
      launch, launch.substring(childStart, childEnd),
      TicketActionPanelDarkLease.panelDarkStopScript("syntax-test"),
      TicketActionPanelDarkLease.panelDarkVerifyScript("syntax-test", 2),
      TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT
    )) {
      val process = ProcessBuilder("/bin/sh", "-n").redirectErrorStream(true).start()
      process.outputStream.bufferedWriter().use { it.write(program) }
      assertTrue(process.waitFor(5, TimeUnit.SECONDS))
      assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
    }
  }

  @Test
  fun acquisitionRequiresBothIndependentIdentityAndDarknessChecks() = fixture { root ->
    fun stat(pid: Int, start: Int) = "$pid (fixture) S " + "0 ".repeat(18) + "$start\n"
    root.resolve("proc/789").mkdirs()
    root.resolve("proc/123").mkdirs()
    root.resolve("proc/789/stat").writeText(stat(789, 321))
    root.resolve("proc/123/stat").writeText(stat(123, 456))
    root.resolve("proc/789/cmdline").writeText(
      listOf("sh", "-c", "fixture", TicketActionPanelDarkLease.HELPER_PROCESS_MARKER,
        "123", "456", "proof-test").joinToString("\u0000", postfix = "\u0000")
    )
    root.resolve("proof-test.ready").writeText(
      "helper_pid=789\nhelper_start=321\nowner_pid=123\nowner_start=456\n"
    )
    root.resolve("panel/panel0-backlight").mkdirs()
    root.resolve("panel/panel0-backlight/brightness").writeText("0\n")
    root.resolve("panel/panel0-backlight/actual_brightness").writeText("0\n")
    val program = TicketActionPanelDarkLease.panelDarkVerifyScript("proof-test", 2)
      .replace(TicketActionPanelDarkLease.HELPER_READINESS_DIRECTORY, root.absolutePath)
      .replace("/proc/", "${root.absolutePath}/proc/")
      .replace("/sys/class/backlight", "${root.absolutePath}/panel")
    // Change the actual fixture between the two complete observations.
    val gap = "usleep 25000 2>/dev/null || sleep 0.025 || exit 76"
    for ((between, expected) in listOf(
      ":" to 0,
      "echo 1 > '${root.absolutePath}/panel/panel0-backlight/brightness'" to 1,
      "rm '${root.absolutePath}/proc/123/stat'" to 74
    )) {
      root.resolve("panel/panel0-backlight/brightness").writeText("0\n")
      root.resolve("proc/123/stat").writeText(stat(123, 456))
      val process = ProcessBuilder("/bin/bash", "-c", program.replace(gap, between))
        .redirectErrorStream(true).start()
      assertTrue(process.waitFor(5, TimeUnit.SECONDS))
      val output = process.inputStream.bufferedReader().readText()
      assertEquals(output, expected, process.exitValue())
      assertEquals(expected == 0, output.contains("panel_confirmations=2"))
      assertEquals(if (expected == 0) 2 else 1, output.lineSequence().count { it == "panel_dark=1" })
    }
  }

  @Test
  fun cleanupCountsEachOwnerOnceAndPreservesUnrelatedFiles() = fixture { root ->
    repeat(16) { index ->
      root.resolve("owner.$index.launch").writeText("owner_pid=123\nowner_start=456\n")
      root.resolve("owner.$index.ready").writeText(
        "helper_pid=789\nhelper_start=321\nowner_pid=123\nowner_start=456\n"
      )
      root.resolve("owner.$index.stage").writeText("ready\n")
      root.resolve("owner.$index.exit").writeText("0\n")
      root.resolve("owner.$index.wait").writeText("")
    }
    root.resolve("unrelated.txt").writeText("keep")
    val result = runCleanup(root)
    assertEquals(result.second, 0, result.first)
    assertTrue(result.second.contains("stale_removed=16"))
    assertEquals(listOf("unrelated.txt"), root.list()!!.sorted())
  }

  @Test
  fun tooManyOwnersOrInvalidTokensCauseNoDeletion() = fixture { root ->
    repeat(17) { root.resolve("owner-$it.stage").writeText("ready\n") }
    assertEquals(78, runCleanup(root).first)
    assertEquals(17, root.list()!!.size)
    root.listFiles()!!.forEach { it.delete() }
    root.resolve("valid.stage").writeText("ready\n")
    root.resolve("invalid token.stage").writeText("ready\n")
    assertEquals(78, runCleanup(root).first)
    assertEquals(2, root.list()!!.size)
  }

  @Test
  fun orphanTelemetryCanBeRemovedButConflictingOwnershipIsRetained() = fixture { root ->
    root.resolve("orphan.stage").writeText("child_started\n")
    root.resolve("orphan.wait").writeText("")
    root.resolve("conflict.launch").writeText("owner_pid=123\nowner_start=456\n")
    root.resolve("conflict.ready").writeText(
      "helper_pid=789\nhelper_start=321\nowner_pid=123\nowner_start=999\n"
    )
    assertEquals(76, runCleanup(root).first)
    assertFalse(root.resolve("orphan.stage").exists())
    assertFalse(root.resolve("orphan.wait").exists())
    assertTrue(root.resolve("conflict.launch").exists())
    assertTrue(root.resolve("conflict.ready").exists())
  }

  private fun fixture(block: (java.io.File) -> Unit) {
    val root = Files.createTempDirectory("ticket-cleanup-").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun runCleanup(root: java.io.File): Pair<Int, String> {
    // Exercise the actual shell program against disposable records. No host
    // process can be found: every /proc access points to an absent fixture tree.
    val script = TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT
      .replace(TicketActionPanelDarkLease.HELPER_READINESS_DIRECTORY, root.absolutePath)
      .replace("/proc/", "${root.absolutePath}/absent-proc/")
    val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
    assertTrue("cleanup exceeded its bounded test deadline", process.waitFor(5, TimeUnit.SECONDS))
    return process.exitValue() to process.inputStream.bufferedReader().readText()
  }
}
