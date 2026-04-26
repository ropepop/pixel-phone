package lv.jolkins.pixelorchestrator.supervisor

import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.health.HealthScope

interface SupervisorControl {
  suspend fun startAll()
  suspend fun resumeSupervision()
  suspend fun stopAll()
  suspend fun startComponent(component: String)
  suspend fun stopComponent(component: String)
  suspend fun restart(component: String)
  suspend fun runHealthCheck(scope: HealthScope): HealthSnapshot
  suspend fun syncDdnsNow()
}
