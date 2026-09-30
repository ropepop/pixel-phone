#!/usr/bin/env python3
"""Run the actual service cleanup callers and helper with the production outbox."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile


def block(source, marker):
    assert source.count(marker) == 1, marker
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path)
parser.add_argument("--caller", choices=("all", "request", "recovery"), default="all")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
ticket = repo / "orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket"
source = (args.source or ticket / "TicketStreamService.kt").read_text()
helper = block(source, "  private fun publishControlCodeReadyAfterPanelFinalization(")
cleanup = block(source, "  private fun sendControlCodeCleanup(")
request = block(source, "          if (publishCleanup) {")
recovery = block(source, "    if (recorded.ok && recoveredControlCodeSurface && recoveredControlCodeCleanupCommitted) {")
fixture = '''package lv.jolkins.pixelorchestrator.app.ticket
private object SystemClock { fun elapsedRealtime() = 100L }
private data class State(val contextRevision: String = "pc-fixture:clean")
private data class Updates(val value: State = State())
private data class PhoneControl(val updates: Updates = Updates())
private data class Outcome(val ok: Boolean)
private class Owner(val streamActive: Boolean) {
  val outbox = TicketSpacetimePhoneOutbox(300_000) { 100L }
  val effects = mutableListOf<String>()
  val phoneControlState = PhoneControl()
  var lastControlCodeRequestReason: String? = null
  val lastControlCodeRequestId: String? = "request"
  val TICKET_SESSION_LIVE = "live"
  fun updateTicketSessionState(state: String, reason: String) { effects += "$state:$reason" }
  fun startForegroundGuard() { effects += "foreground_guard" }
  fun recordTicketEvent(event: String, detail: String) {}
  fun enqueueTicketCodePublication(value: TicketCodePublication) { outbox.enqueue(value) }
''' + helper + '\n' + cleanup + '''
  fun finish(generatedResultDelivered: Boolean, browserCaptureFailure: String?, failure: String?, publishCleanup: Boolean = true) {
    val cleanRequestId = "request"
    val deferredCleanupReason = "return_to_raw_complete"
    val startedAtMillis = 0L
''' + request + '''
  }
  fun recover(recordedOk: Boolean = true, surfaceCleaned: Boolean = true, committed: Boolean = true) {
    val recorded = Outcome(recordedOk)
    val recoveredControlCodeSurface = surfaceCleaned
    val recoveredControlCodeCleanupCommitted = committed
''' + recovery + '''
  }
}
fun main() {
  var cases = 0
  val caller = "CALLER_SELECTION"
  for (stream in listOf(false, true)) {
    fun checkPublications(owner: Owner, kinds: List<TicketCodePublicationKind>, captured: Boolean) {
      val received = mutableListOf<TicketCodePublicationKind>()
      while (true) {
        val next = owner.outbox.peek() ?: break
        // The existing server refuses READY without a captured successful result.
        check(next.kind != TicketCodePublicationKind.READY || captured) {
          "control_code_cleanup_not_authorized: READY blocks the actual outbox before CLEANUP"
        }
        if (next.kind == TicketCodePublicationKind.CLEANUP) {
          check(!next.cleanupPending)
          check(next.status.isEmpty()) // Preserve the server's existing terminal status.
          check(next.reducerArguments("ticket", "pixel", "now").takeLast(2) == listOf(false, "now"))
        }
        received += next.kind
        owner.outbox.acknowledge(next)
      }
      check(received == kinds) { "unexpected publication order: $received" }
      check(owner.effects == if (stream) listOf("live:control_exit_popup_closed", "foreground_guard") else emptyList<String>())
      cases++
    }
    if (caller != "recovery") {
      val failed = Owner(stream)
      failed.outbox.enqueue(TicketCodePublication("request", TicketCodePublicationKind.FAILURE, status = "failed", cleanupPending = true))
      failed.finish(false, null, null)
      checkPublications(failed, listOf(TicketCodePublicationKind.FAILURE, TicketCodePublicationKind.CLEANUP), false)
      val success = Owner(stream)
      success.finish(true, null, null)
      checkPublications(success, listOf(TicketCodePublicationKind.READY, TicketCodePublicationKind.CLEANUP), true)
      val uncommitted = Owner(stream)
      uncommitted.finish(true, null, null, publishCleanup = false)
      check(uncommitted.outbox.peek() == null && uncommitted.effects.isEmpty())
      cases++
    }
    if (caller != "request") {
      for (status in listOf("failed", "uncaptured", "captured")) {
        val recovered = Owner(stream)
        val priorKind = if (status == "failed") TicketCodePublicationKind.FAILURE else TicketCodePublicationKind.GENERATED
        recovered.outbox.enqueue(TicketCodePublication("request", priorKind,
          status = if (status == "failed") "failed" else "succeeded", cleanupPending = true,
          epoch = 7, sequence = 11, proof = "generated_visual", proofAt = "2026-09-30T00:00:00Z"))
        recovered.recover()
        checkPublications(recovered, listOf(priorKind, TicketCodePublicationKind.CLEANUP), status == "captured")
      }
      for (denial in 0..2) {
        val denied = Owner(stream)
        denied.recover(recordedOk = denial != 0, surfaceCleaned = denial != 1, committed = denial != 2)
        check(denied.outbox.peek() == null && denied.effects.isEmpty())
        cases++
      }
    }
    if (caller != "recovery") {
      for (generated in listOf(false, true)) {
        val unsafe = Owner(stream)
        unsafe.finish(generated, if (generated) "browser_capture_failed" else null, "cleanup_unproved")
        check(unsafe.effects.isEmpty())
        check(unsafe.outbox.peek()?.kind == TicketCodePublicationKind.CLEANUP)
        check(unsafe.outbox.peek()?.cleanupPending == true)
        cases++
      }
    }
  }
  println("PASS $cases actual cleanup caller/outbox cases; failed and recovered cleanup drains, captured READY retained, local effects and denial fences preserved")
}
'''.replace("CALLER_SELECTION", args.caller)
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
artifacts = [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.10"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.10"),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.1.10"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
    ("org.jetbrains", "annotations", "13.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.1"),
]
jars = [next((cache / group / name / version).glob("*/*.jar")) for group, name, version in artifacts]
classpath = os.pathsep.join(map(str, jars))
java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
java = str(Path(java_home) / "bin/java")
with tempfile.TemporaryDirectory(prefix="ticket-code-cleanup-") as directory:
    root = Path(directory)
    caller = root / "ActualCaller.kt"
    caller.write_text(fixture)
    subprocess.run([java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", str(root / "classes"),
                    str(caller), str(ticket / "TicketSpacetimePhoneOutbox.kt")], check=True)
    subprocess.run([java, "-cp", str(root / "classes") + os.pathsep + classpath,
                    "lv.jolkins.pixelorchestrator.app.ticket.ActualCallerKt"], check=True)
