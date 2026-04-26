package lv.jolkins.pixelorchestrator.rootexec

data class RootResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
  val command: String,
  val durationMs: Long
) {
  val ok: Boolean get() = exitCode == 0
}
