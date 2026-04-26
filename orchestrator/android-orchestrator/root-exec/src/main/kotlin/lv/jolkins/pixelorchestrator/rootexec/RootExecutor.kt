package lv.jolkins.pixelorchestrator.rootexec

import kotlin.time.Duration

interface RootExecutor {
  suspend fun isRootAvailable(): Boolean
  suspend fun run(command: String, timeout: Duration = Duration.parse("30s")): RootResult
  suspend fun runScript(script: String, timeout: Duration = Duration.parse("120s")): RootResult
}
