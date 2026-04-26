package lv.jolkins.pixelorchestrator.health

fun interface CommandRunner {
  suspend fun run(command: String): CommandResult
}

data class CommandResult(
  val ok: Boolean,
  val stdout: String,
  val stderr: String
)
