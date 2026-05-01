package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper
import kotlin.time.Duration.Companion.seconds

class TicketRuntimeStateStore(
  private val rootExecutor: RootExecutor,
  private val json: Json
) {
  suspend fun save(snapshot: TicketRuntimeSnapshot) {
    val payload = json.encodeToString(snapshot)
    val quoted = ShellEscaper.singleQuote(payload)
    rootExecutor.runScript(
      """
      state_dir="$STATE_DIR"
      mkdir -p "${'$'}state_dir"
      printf '%s\n' $quoted > "${'$'}state_dir/runtime-state.json.tmp"
      mv "${'$'}state_dir/runtime-state.json.tmp" "${'$'}state_dir/runtime-state.json"
      chmod 600 "${'$'}state_dir/runtime-state.json" >/dev/null 2>&1 || true
      """.trimIndent(),
      timeout = 5.seconds
    )
  }

  companion object {
    const val STATE_DIR = "/data/local/pixel-stack/apps/ticket-screen/state"
  }
}

@Serializable
data class TicketRuntimeSnapshot(
  val savedAtElapsedMillis: Long,
  val sessionState: String,
  val streamActive: Boolean,
  val captureMode: String,
  val rootCapture: TicketRootCaptureHealth,
  val lastGoodStreamAgoMillis: Long?,
  val fallbackReason: String?,
  val recoveryCounters: TicketRecoveryCounters
)

@Serializable
data class TicketRecoveryCounters(
  val rootCaptureRestarts: Long = 0L,
  val keyFrameRequests: Long = 0L,
  val encodedFrames: Long = 0L,
  val sentFrames: Long = 0L
)
