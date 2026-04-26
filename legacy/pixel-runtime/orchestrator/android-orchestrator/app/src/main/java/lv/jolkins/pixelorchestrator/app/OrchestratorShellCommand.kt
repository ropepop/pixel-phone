package lv.jolkins.pixelorchestrator.app

object OrchestratorShellCommand {
  const val EXTRA_ACTION = "orchestrator_action"
  const val EXTRA_COMPONENT = "orchestrator_component"
  const val EXTRA_PIXEL_RUN_ID = "pixel_run_id"
  const val EXTRA_DRY_RUN = "orchestrator_dry_run"

  const val ACTION_BOOTSTRAP = "bootstrap"
  const val ACTION_START_ALL = "start_all"
  const val ACTION_STOP_ALL = "stop_all"
  const val ACTION_HEALTH = "health"
  const val ACTION_START_COMPONENT = "start_component"
  const val ACTION_STOP_COMPONENT = "stop_component"
  const val ACTION_RESTART_COMPONENT = "restart_component"
  const val ACTION_REDEPLOY_COMPONENT = "redeploy_component"
  const val ACTION_HEALTH_COMPONENT = "health_component"
  const val ACTION_SYNC_DDNS = "sync_ddns"
  const val ACTION_EXPORT_BUNDLE = "export_bundle"
  const val ACTION_CLEANUP = "cleanup"

  fun normalizeAction(value: String?): String = value?.trim()?.lowercase().orEmpty()

  fun toSupervisorAction(action: String): String? =
    when (normalizeAction(action)) {
      ACTION_BOOTSTRAP -> SupervisorService.ACTION_BOOTSTRAP
      ACTION_START_ALL -> SupervisorService.ACTION_START_ALL
      ACTION_STOP_ALL -> SupervisorService.ACTION_STOP_ALL
      ACTION_HEALTH -> SupervisorService.ACTION_HEALTH
      ACTION_START_COMPONENT -> SupervisorService.ACTION_START_COMPONENT
      ACTION_STOP_COMPONENT -> SupervisorService.ACTION_STOP_COMPONENT
      ACTION_RESTART_COMPONENT -> SupervisorService.ACTION_RESTART_COMPONENT
      ACTION_REDEPLOY_COMPONENT -> SupervisorService.ACTION_REDEPLOY_COMPONENT
      ACTION_HEALTH_COMPONENT -> SupervisorService.ACTION_HEALTH_COMPONENT
      ACTION_SYNC_DDNS -> SupervisorService.ACTION_SYNC_DDNS
      ACTION_EXPORT_BUNDLE -> SupervisorService.ACTION_EXPORT_BUNDLE
      ACTION_CLEANUP -> SupervisorService.ACTION_CLEANUP
      else -> null
    }

  fun fromSupervisorAction(action: String?): String? =
    when (action?.trim()) {
      SupervisorService.ACTION_BOOTSTRAP -> ACTION_BOOTSTRAP
      SupervisorService.ACTION_START_ALL -> ACTION_START_ALL
      SupervisorService.ACTION_STOP_ALL -> ACTION_STOP_ALL
      SupervisorService.ACTION_HEALTH -> ACTION_HEALTH
      SupervisorService.ACTION_START_COMPONENT -> ACTION_START_COMPONENT
      SupervisorService.ACTION_STOP_COMPONENT -> ACTION_STOP_COMPONENT
      SupervisorService.ACTION_RESTART_COMPONENT -> ACTION_RESTART_COMPONENT
      SupervisorService.ACTION_REDEPLOY_COMPONENT -> ACTION_REDEPLOY_COMPONENT
      SupervisorService.ACTION_HEALTH_COMPONENT -> ACTION_HEALTH_COMPONENT
      SupervisorService.ACTION_SYNC_DDNS -> ACTION_SYNC_DDNS
      SupervisorService.ACTION_EXPORT_BUNDLE -> ACTION_EXPORT_BUNDLE
      SupervisorService.ACTION_CLEANUP -> ACTION_CLEANUP
      else -> null
    }
}
