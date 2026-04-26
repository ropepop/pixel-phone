package lv.jolkins.pixelorchestrator.supervisor

interface ComponentController {
  val name: String
  suspend fun start(): Boolean
  suspend fun stop(): Boolean
  suspend fun health(): Boolean
}
