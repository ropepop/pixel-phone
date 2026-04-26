package lv.jolkins.pixelorchestrator.supervisor

import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState

interface ComponentController {
  val name: String
  suspend fun start(): Boolean
  suspend fun stop(): Boolean
  suspend fun health(): Boolean
}

interface AutoStartAwareComponentController {
  suspend fun shouldAutoStart(): Boolean
}

interface ModuleHealthAwareComponentController {
  suspend fun moduleHealthState(): ModuleHealthState
}
