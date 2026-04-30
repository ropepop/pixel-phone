package lv.jolkins.pixelorchestrator.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log

class OrchestratorActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (!isTrustedSender()) {
      Log.w(TAG, "command_rejected reason=untrusted_sender uid=${senderUidForLog()}")
      return
    }
    val action = OrchestratorShellCommand.normalizeAction(intent?.getStringExtra(OrchestratorShellCommand.EXTRA_ACTION))
    val component = intent?.getStringExtra(OrchestratorShellCommand.EXTRA_COMPONENT)?.trim().orEmpty()
    val pixelRunId = intent?.getStringExtra(OrchestratorShellCommand.EXTRA_PIXEL_RUN_ID)?.trim().orEmpty()
    val dryRun = intent?.getBooleanExtra(OrchestratorShellCommand.EXTRA_DRY_RUN, false) ?: false
    if (action.isBlank()) {
      Log.w(TAG, "command_rejected reason=missing_action component=$component run_id=$pixelRunId")
      return
    }

    val supervisorAction = OrchestratorShellCommand.toSupervisorAction(action)
    if (supervisorAction == null) {
      Log.w(TAG, "command_rejected action=$action component=$component run_id=$pixelRunId reason=unknown_action")
      return
    }

    Log.i(TAG, "command_accepted action=$action component=$component run_id=$pixelRunId")
    SupervisorService.start(
      context = context,
      action = supervisorAction,
      component = component,
      pixelRunId = pixelRunId,
      commandAction = action,
      dryRun = dryRun,
      cleanupTrigger = CleanupTrigger.MANUAL.wireValue()
    )
  }

  companion object {
    private const val TAG = "OrchestratorActionReceiver"

    private fun OrchestratorActionReceiver.isTrustedSender(): Boolean {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return true
      }
      return senderUidForLog() in setOf(
        Process.ROOT_UID,
        Process.SHELL_UID,
        Process.SYSTEM_UID,
        Process.myUid()
      )
    }

    private fun OrchestratorActionReceiver.senderUidForLog(): Int {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        sentFromUid
      } else {
        -1
      }
    }
  }
}
