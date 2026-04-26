package lv.jolkins.pixelorchestrator.app

import lv.jolkins.pixelorchestrator.health.CommandResult
import lv.jolkins.pixelorchestrator.health.CommandRunner
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

class RootCommandRunnerAdapter(
  private val rootExecutor: RootExecutor
) : CommandRunner {
  override suspend fun run(command: String): CommandResult {
    val result = rootExecutor.run(command)
    return CommandResult(
      ok = result.ok,
      stdout = result.stdout,
      stderr = result.stderr
    )
  }
}
