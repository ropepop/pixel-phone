package lv.jolkins.pixelorchestrator.supervisor

import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

class RootScriptController(
  override val name: String,
  private val rootExecutor: RootExecutor,
  private val startCommand: String,
  private val stopCommand: String,
  private val healthCommand: String
) : ComponentController {

  override suspend fun start(): Boolean {
    return rootExecutor.run(startCommand).ok
  }

  override suspend fun stop(): Boolean {
    return rootExecutor.run(stopCommand).ok
  }

  override suspend fun health(): Boolean {
    return rootExecutor.run(healthCommand).ok
  }
}
