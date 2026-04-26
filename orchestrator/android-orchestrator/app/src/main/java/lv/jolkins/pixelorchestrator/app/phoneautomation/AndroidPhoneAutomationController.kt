package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import lv.jolkins.pixelorchestrator.app.MainActivity
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal data class PhoneAutomationResolvedTarget(
  val profile: PhoneAutomationTargetProfile,
  val packageName: String,
  val launcherLabel: String,
  val versionName: String,
  val launchIntent: Intent
)

internal data class PhoneAutomationResolvedTargets(
  val speedtest: PhoneAutomationResolvedTarget?,
  val cellMapper: PhoneAutomationResolvedTarget?
) {
  fun target(app: PhoneAutomationApp): PhoneAutomationResolvedTarget? {
    return when (app) {
      PhoneAutomationApp.SPEEDTEST -> speedtest
      PhoneAutomationApp.CELLMAPPER -> cellMapper
    }
  }
}

private sealed interface SpeedtestSelectorAttemptResult {
  data object Clicked : SpeedtestSelectorAttemptResult
  data object RetryAfterFocusRecovery : SpeedtestSelectorAttemptResult

  data class StableMismatch(
    val detail: String
  ) : SpeedtestSelectorAttemptResult

  data class TransientFailure(
    val detail: String,
    val failureCategory: PhoneAutomationFailureCategory = PhoneAutomationFailureCategory.GENERIC,
    val failureCleanupDisposition: PhoneAutomationFailureCleanupDisposition =
      PhoneAutomationFailureCleanupDisposition.NONE
  ) : SpeedtestSelectorAttemptResult
}

private sealed interface SpeedtestUiAttemptResult {
  data class Success(
    val evidence: SpeedtestLaunchEvidence
  ) : SpeedtestUiAttemptResult

  data class StableNoMatch(
    val detail: String
  ) : SpeedtestUiAttemptResult

  data class TransientFailure(
    val detail: String,
    val failureCategory: PhoneAutomationFailureCategory = PhoneAutomationFailureCategory.GENERIC,
    val failureCleanupDisposition: PhoneAutomationFailureCleanupDisposition =
      PhoneAutomationFailureCleanupDisposition.NONE,
    val failureRecovery: SpeedtestFailureRecovery = SpeedtestFailureRecovery.NONE,
    val recoveryReasonKey: String = "",
    val evidence: SpeedtestLaunchEvidence = SpeedtestLaunchEvidence()
  ) : SpeedtestUiAttemptResult
}

private enum class SpeedtestFailureRecovery {
  NONE,
  FORCE_STOP_RELAUNCH
}

private sealed interface SpeedtestRunningProofResult {
  data class Success(
    val evidence: SpeedtestLaunchEvidence
  ) : SpeedtestRunningProofResult

  data class TransientFailure(
    val detail: String,
    val failureCategory: PhoneAutomationFailureCategory = PhoneAutomationFailureCategory.GENERIC,
    val failureCleanupDisposition: PhoneAutomationFailureCleanupDisposition =
      PhoneAutomationFailureCleanupDisposition.NONE,
    val failureRecovery: SpeedtestFailureRecovery = SpeedtestFailureRecovery.NONE,
    val recoveryReasonKey: String = "",
    val evidence: SpeedtestLaunchEvidence = SpeedtestLaunchEvidence()
  ) : SpeedtestRunningProofResult
}

private data class SpeedtestLaunchEvidence(
  val selectorDescription: String = "",
  val runningNotificationSeen: Boolean = false,
  val connectingSeen: Boolean = false,
  val connectingCleared: Boolean = false,
  val fallbackForegroundProof: Boolean = false,
  val interruptedByOrchestrator: Boolean = false
)

private data class SpeedtestLaunchPlan(
  val initialPhase: String,
  val primarySelectorAttempts: List<Pair<PhoneAutomationSelectorKind, String>>,
  val relaunchSelectorAttempts: List<Pair<PhoneAutomationSelectorKind, String>>,
  val primaryNoMatchDetail: String,
  val relaunchNoMatchDetail: String,
  val primarySuccessPath: PhoneAutomationRestartPath? = null,
  val relaunchSuccessPath: PhoneAutomationRestartPath? = null,
  val buildPrimarySuccessDetail: (SpeedtestLaunchEvidence) -> String,
  val relaunchSuccessDetail: String
)

private data class SpeedtestLaunchOutcome(
  val success: Boolean,
  val detail: String,
  val path: PhoneAutomationRestartPath? = null,
  val failureMode: PhoneAutomationFailureMode = PhoneAutomationFailureMode.PERMANENT,
  val failureCategory: PhoneAutomationFailureCategory = PhoneAutomationFailureCategory.GENERIC,
  val failureCleanupDisposition: PhoneAutomationFailureCleanupDisposition =
    PhoneAutomationFailureCleanupDisposition.NONE,
  val retryExhaustionDisposition: PhoneAutomationRetryExhaustionDisposition =
    PhoneAutomationRetryExhaustionDisposition.BLOCK_SETUP,
  val recoveryReasonKey: String = ""
)

internal fun buildPreparationMissingRequirements(
  mode: PhoneAutomationMode,
  hasRequiredSelectors: Boolean,
  rootAvailable: Boolean,
  targets: PhoneAutomationResolvedTargets,
  accessibilityPermissionEnabled: Boolean,
  notificationAccessPermissionEnabled: Boolean,
  reliability: PhoneAutomationBackgroundReliability
): List<String> {
  val missingRequirements = mutableListOf<String>()
  if (!hasRequiredSelectors) {
    missingRequirements += "One or more required app selectors are missing"
  }
  if (!rootAvailable) {
    missingRequirements += "Root access is unavailable"
  }
  if (targets.speedtest == null) {
    missingRequirements += "Speedtest by Ookla is not installed"
  }
  if (mode.maintainsCellMapper && targets.cellMapper == null) {
    missingRequirements += "CellMapper is not installed"
  }
  if (!accessibilityPermissionEnabled) {
    missingRequirements += "Accessibility access is not enabled"
  }
  if (!notificationAccessPermissionEnabled) {
    missingRequirements += "Notification access is not enabled"
  }
  missingRequirements += PhoneAutomationBackgroundReliabilitySupport.missingAutomationRequirements(reliability)
  return missingRequirements
}

internal fun buildPreparationReadyDetail(
  mode: PhoneAutomationMode,
  targets: PhoneAutomationResolvedTargets
): String {
  val speedtest = checkNotNull(targets.speedtest) { "Speedtest target is required when automation is ready" }
  return buildString {
    append("Ready: ")
    append("${speedtest.profile.displayName} ${speedtest.versionName} (${speedtest.packageName})")
    if (mode.maintainsCellMapper) {
      val cellMapper = checkNotNull(targets.cellMapper) { "CellMapper target is required in full mode" }
      append(", ")
      append("${cellMapper.profile.displayName} ${cellMapper.versionName} (${cellMapper.packageName})")
    } else {
      append(" (CellMapper maintenance disabled)")
    }
  }
}

internal class PhoneAutomationTargetResolver(
  private val context: Context
) {
  private val packageManager: PackageManager = context.packageManager

  fun resolveAll(): PhoneAutomationResolvedTargets {
    return PhoneAutomationResolvedTargets(
      speedtest = resolve(PhoneAutomationProfiles.profile(PhoneAutomationApp.SPEEDTEST)),
      cellMapper = resolve(PhoneAutomationProfiles.profile(PhoneAutomationApp.CELLMAPPER))
    )
  }

  private fun resolve(profile: PhoneAutomationTargetProfile): PhoneAutomationResolvedTarget? {
    profile.packageCandidates.firstNotNullOfOrNull { candidatePackage ->
      createTargetFromPackage(profile, candidatePackage)
    }?.let { return it }

    val labelMatch = queryLauncherActivities().firstOrNull { resolveInfo ->
      val label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty()
      profile.launcherLabelKeywords.any { keyword -> label.contains(keyword, ignoreCase = true) }
    } ?: return null

    val packageName = labelMatch.activityInfo.packageName
    return createTargetFromResolveInfo(profile, packageName, labelMatch)
  }

  private fun createTargetFromPackage(
    profile: PhoneAutomationTargetProfile,
    packageName: String
  ): PhoneAutomationResolvedTarget? {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    val resolveInfo = resolveActivityForLaunchIntent(launchIntent)
    val label = resolveInfo?.loadLabel(packageManager)?.toString()
      ?: packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    return PhoneAutomationResolvedTarget(
      profile = profile,
      packageName = packageName,
      launcherLabel = label,
      versionName = packageInfo(packageName)?.versionName.orEmpty(),
      launchIntent = Intent(launchIntent)
    )
  }

  private fun createTargetFromResolveInfo(
    profile: PhoneAutomationTargetProfile,
    packageName: String,
    resolveInfo: ResolveInfo
  ): PhoneAutomationResolvedTarget? {
    val launchIntent = Intent(Intent.ACTION_MAIN)
      .addCategory(Intent.CATEGORY_LAUNCHER)
      .setComponent(ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name))
    return PhoneAutomationResolvedTarget(
      profile = profile,
      packageName = packageName,
      launcherLabel = resolveInfo.loadLabel(packageManager)?.toString().orEmpty(),
      versionName = packageInfo(packageName)?.versionName.orEmpty(),
      launchIntent = launchIntent
    )
  }

  private fun queryLauncherActivities(): List<ResolveInfo> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.queryIntentActivities(
        launcherIntent,
        PackageManager.ResolveInfoFlags.of(0L)
      )
    } else {
      packageManager.queryIntentActivities(launcherIntent, 0)
    }
  }

  private fun resolveActivityForLaunchIntent(launchIntent: Intent): ResolveInfo? {
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.resolveActivity(
        launchIntent,
        PackageManager.ResolveInfoFlags.of(0L)
      )
    } else {
      packageManager.resolveActivity(launchIntent, 0)
    }
  }

  private fun packageInfo(packageName: String): PackageInfo? {
    return runCatching {
      @Suppress("DEPRECATION")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
      } else {
        packageManager.getPackageInfo(packageName, 0)
      }
    }.getOrNull()
  }
}

internal class AndroidPhoneAutomationController(
  private val context: Context,
  private val rootExecutor: RootExecutor,
  private val providedTargetResolver: PhoneAutomationTargetResolver? = null,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge,
  private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : PhoneAutomationController {
  private var resolvedTargets: PhoneAutomationResolvedTargets? = null
  private val targetResolver: PhoneAutomationTargetResolver by lazy {
    providedTargetResolver ?: PhoneAutomationTargetResolver(context)
  }
  private val accessibilityRecovery: PhoneAutomationAccessibilityRecovery by lazy {
    PhoneAutomationAccessibilityRecovery(
      environment = AndroidPhoneAutomationAccessibilityRecoveryEnvironment(
        context = context,
        rootExecutor = rootExecutor,
        bridge = bridge
      )
    )
  }

  override val events: Flow<PhoneAutomationEvent> = bridge.notificationEvents.mapNotNull { event ->
    val speedtestCompleted = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.SPEEDTEST)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.SPEEDTEST_COMPLETED)
    val cellMapperStatus = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.CELLMAPPER)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.CELLMAPPER_STATUS)
    val cellMapperRecording = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.CELLMAPPER)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.CELLMAPPER_RECORDING)

    when (event) {
      is PhoneAutomationNotificationEvent.Posted ->
        if (speedtestCompleted.matches(event.notification)) {
          PhoneAutomationEvent.SpeedtestCompleted(event.observedAtMillis)
        } else if (cellMapperStatus.matches(event.notification) && !cellMapperRecording.matches(event.notification)) {
          PhoneAutomationEvent.CellMapperRecordingStopped(event.observedAtMillis)
        } else {
          null
        }

      is PhoneAutomationNotificationEvent.Removed ->
        if (cellMapperStatus.matches(event.notification)) {
          PhoneAutomationEvent.CellMapperRecordingStopped(event.observedAtMillis)
        } else {
          null
        }
    }
  }

  override suspend fun prepare(mode: PhoneAutomationMode): PhoneAutomationPreparationResult {
    val rootAvailable = rootExecutor.isRootAvailable()
    val targets = targetResolver.resolveAll()
    val reliability = PhoneAutomationBackgroundReliabilitySupport.read(context)

    if (rootAvailable) {
      attemptPermissionRepairIfNeeded()
    }
    val missingRequirements = buildPreparationMissingRequirements(
      mode = mode,
      hasRequiredSelectors = PhoneAutomationProfiles.hasRequiredSelectors(mode),
      rootAvailable = rootAvailable,
      targets = targets,
      accessibilityPermissionEnabled = bridge.isAccessibilityPermissionEnabled(context),
      notificationAccessPermissionEnabled = bridge.isNotificationAccessPermissionEnabled(context),
      reliability = reliability
    ).toMutableList()

    if (missingRequirements.isNotEmpty()) {
      return PhoneAutomationPreparationResult(
        ready = false,
        detail = missingRequirements.joinToString(separator = "; "),
        failureMode = PhoneAutomationFailureMode.PERMANENT
      )
    }

    var accessibilityConnected = bridge.awaitAccessibilityConnection(SERVICE_CONNECTION_TIMEOUT_MILLIS)
    if (!accessibilityConnected && rootAvailable) {
      val recovery = attemptAccessibilityRebindIfNeeded()
      if (recovery.recovered) {
        accessibilityConnected = true
      }
    }
    if (!accessibilityConnected) {
      missingRequirements += "Accessibility service is not connected"
    }
    val notificationConnected = bridge.awaitNotificationListenerConnection(SERVICE_CONNECTION_TIMEOUT_MILLIS)
    if (!notificationConnected) {
      missingRequirements += "Notification listener is not connected"
    }
    if (missingRequirements.isNotEmpty()) {
      return PhoneAutomationPreparationResult(
        ready = false,
        detail = missingRequirements.joinToString(separator = "; "),
        failureMode = PhoneAutomationFailureMode.TRANSIENT
      )
    }

    resolvedTargets = targets
    return PhoneAutomationPreparationResult(
      ready = true,
      detail = buildPreparationReadyDetail(mode, targets)
    )
  }

  override suspend fun speedtestState(): SpeedtestActivityState {
    if (!bridge.isNotificationBootstrapReady()) {
      return SpeedtestActivityState.UNKNOWN
    }
    val matcher = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.SPEEDTEST)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.SPEEDTEST_RUNNING)
    return if (bridge.isNotificationPresent(matcher)) {
      SpeedtestActivityState.RUNNING
    } else {
      SpeedtestActivityState.NOT_RUNNING
    }
  }

  override suspend fun cellMapperState(): CellMapperRecordingState {
    if (!bridge.isNotificationBootstrapReady()) {
      return CellMapperRecordingState.UNKNOWN
    }
    val matcher = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.CELLMAPPER)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.CELLMAPPER_RECORDING)
    return if (bridge.isNotificationPresent(matcher)) {
      CellMapperRecordingState.ACTIVE
    } else {
      CellMapperRecordingState.INACTIVE
    }
  }

  override suspend fun awaitNotificationBootstrap(timeoutMillis: Long): Boolean {
    return bridge.awaitNotificationBootstrap(timeoutMillis)
  }

  override fun isCellMapperRecordingActive(): Boolean {
    val matcher = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.CELLMAPPER)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.CELLMAPPER_RECORDING)
    return bridge.isNotificationPresent(matcher)
  }

  override suspend fun speedtestCompletionEvidence(runStartedAtMillis: Long): SpeedtestCompletionEvidence {
    val target = resolvedTarget(PhoneAutomationApp.SPEEDTEST) ?: return SpeedtestCompletionEvidence()
    val completionMatcher = target.profile.notificationMatchers.getValue(
      PhoneAutomationNotificationKind.SPEEDTEST_COMPLETED
    )
    val retrySelectors = target.profile.selectors.getValue(PhoneAutomationSelectorKind.SPEEDTEST_RETRY)
    val completionNotificationAtMillis = bridge.matchingNotifications(completionMatcher)
      .filter { notification -> notification.postedAtMillis >= runStartedAtMillis }
      .maxOfOrNull { notification -> notification.postedAtMillis }
      ?: 0L
    val resultReadyAtMillis = if (
      bridge.isSelectorPresent(
        expectedPackageName = target.packageName,
        selectors = retrySelectors
      )
    ) {
      clockMillis()
    } else {
      0L
    }
    return SpeedtestCompletionEvidence(
      completionNotificationAtMillis = completionNotificationAtMillis,
      resultReadyAtMillis = resultReadyAtMillis,
      resultFingerprint = if (resultReadyAtMillis > 0L) {
        speedtestResultFingerprint(target)
      } else {
        ""
      }
    )
  }

  override suspend fun recoverCellMapper(reason: String): PhoneAutomationActionResult {
    val target = resolvedTarget(PhoneAutomationApp.CELLMAPPER) ?: return missingTargetResult(PhoneAutomationApp.CELLMAPPER)
    if (isCellMapperRecordingActive()) {
      return PhoneAutomationActionResult(true, "CellMapper is already recording")
    }
    val launchResult = launchTarget(target)
    if (!launchResult.success) {
      return launchResult
    }
    if (isCellMapperRecordingActive()) {
      return PhoneAutomationActionResult(true, "CellMapper recording returned after relaunch")
    }
    val selectors = target.profile.selectors.getValue(PhoneAutomationSelectorKind.CELLMAPPER_RECORD_TOGGLE)
    val clicked = bridge.clickSelectors(
      expectedPackageName = target.packageName,
      selectors = selectors,
      timeoutMillis = CELL_MAPPER_SELECTOR_TIMEOUT_MILLIS
    )
    if (!clicked) {
      return PhoneAutomationActionResult(
        false,
        "CellMapper record toggle selector did not match",
        PhoneAutomationFailureMode.PERMANENT
      )
    }
    val restored = waitForNotification(
      matcher = target.profile.notificationMatchers.getValue(PhoneAutomationNotificationKind.CELLMAPPER_RECORDING),
      timeoutMillis = CELL_MAPPER_NOTIFICATION_TIMEOUT_MILLIS
    )
    return if (restored) {
      PhoneAutomationActionResult(true, "CellMapper recording recovered after $reason")
    } else {
      PhoneAutomationActionResult(
        false,
        "CellMapper recording did not resume",
        PhoneAutomationFailureMode.PERMANENT
      )
    }
  }

  override suspend fun startSpeedtest(reason: String): PhoneAutomationActionResult {
    val target = resolvedTarget(PhoneAutomationApp.SPEEDTEST) ?: return missingTargetResult(PhoneAutomationApp.SPEEDTEST)
    bridge.recordExpectedOrchestratorForeground()
    val forceStop = rootExecutor.run("am force-stop ${target.packageName}")
    if (!forceStop.ok) {
      return PhoneAutomationActionResult(
        success = false,
        detail = forceStop.stderr.ifBlank { forceStop.stdout.ifBlank { "Speedtest cleanup failed" } },
        failureMode = PhoneAutomationFailureMode.TRANSIENT
      )
    }
    delay(FORCE_STOP_SETTLE_DELAY_MILLIS)
    val outcome = runSpeedtestLaunch(
      target = target,
      reason = reason,
      plan = SpeedtestLaunchPlan(
        initialPhase = "cold_start",
        primarySelectorAttempts = listOf(
          PhoneAutomationSelectorKind.SPEEDTEST_START to "start button"
        ),
        relaunchSelectorAttempts = emptyList(),
        primaryNoMatchDetail = "Speedtest start button was unavailable after fresh launch",
        relaunchNoMatchDetail = "Speedtest start button was unavailable after fresh launch",
        buildPrimarySuccessDetail = { evidence ->
          "Started Speedtest from a fresh launch with the ${evidence.selectorDescription} after $reason"
        },
        relaunchSuccessDetail = "Started Speedtest from a fresh launch after $reason"
      )
    )
    return PhoneAutomationActionResult(
      success = outcome.success,
      detail = outcome.detail,
      failureMode = outcome.failureMode,
      failureCategory = outcome.failureCategory,
      failureCleanupDisposition = outcome.failureCleanupDisposition,
      retryExhaustionDisposition = outcome.retryExhaustionDisposition,
      recoveryReasonKey = outcome.recoveryReasonKey
    )
  }

  override suspend fun restartSpeedtest(reason: String): PhoneAutomationRestartResult {
    val outcome = runSpeedtestLaunch(
      target = resolvedTarget(PhoneAutomationApp.SPEEDTEST),
      reason = reason,
      plan = SpeedtestLaunchPlan(
        initialPhase = "warm_restart",
        primarySelectorAttempts = listOf(
          PhoneAutomationSelectorKind.SPEEDTEST_RETRY to "retry button"
        ),
        relaunchSelectorAttempts = emptyList(),
        primaryNoMatchDetail = "Speedtest retry button was unavailable",
        relaunchNoMatchDetail = "Speedtest retry button was unavailable",
        primarySuccessPath = PhoneAutomationRestartPath.RETRY_BUTTON,
        relaunchSuccessPath = null,
        buildPrimarySuccessDetail = {
          "Restarted Speedtest with the visible retry button after $reason"
        },
        relaunchSuccessDetail = "Restarted Speedtest with the visible retry button after $reason"
      )
    )
    return PhoneAutomationRestartResult(
      success = outcome.success,
      path = outcome.path ?: PhoneAutomationRestartPath.FAILED,
      detail = outcome.detail,
      failureMode = outcome.failureMode,
      failureCategory = outcome.failureCategory,
      failureCleanupDisposition = outcome.failureCleanupDisposition,
      retryExhaustionDisposition = outcome.retryExhaustionDisposition,
      recoveryReasonKey = outcome.recoveryReasonKey
    )
  }

  override suspend fun bringSpeedtestToForeground(reason: String): PhoneAutomationActionResult {
    val target = resolvedTarget(PhoneAutomationApp.SPEEDTEST) ?: return missingTargetResult(PhoneAutomationApp.SPEEDTEST)
    val launch = launchTarget(target)
    return if (launch.success) {
      PhoneAutomationActionResult(true, "Returned Speedtest to the foreground after $reason")
    } else {
      launch
    }
  }

  override suspend fun cleanupFailedSpeedtestLaunch(
    reason: String,
    restoreCellMapper: Boolean
  ): PhoneAutomationActionResult {
    val speedtestTarget = resolvedTarget(PhoneAutomationApp.SPEEDTEST)
    if (speedtestTarget != null) {
      bridge.recordExpectedOrchestratorForeground()
      val forceStop = rootExecutor.run("am force-stop ${speedtestTarget.packageName}")
      if (!forceStop.ok) {
        return PhoneAutomationActionResult(
          success = false,
          detail = forceStop.stderr.ifBlank { forceStop.stdout.ifBlank { "Speedtest cleanup failed" } },
          failureMode = PhoneAutomationFailureMode.TRANSIENT
        )
      }
      delay(FORCE_STOP_SETTLE_DELAY_MILLIS)
    }

    if (restoreCellMapper) {
      val restore = bringCellMapperToForeground(reason = "failed_speedtest_launch:$reason")
      if (!restore.success) {
        return restore
      }
    }

    return PhoneAutomationActionResult(
      success = true,
      detail = "Cleaned up failed Speedtest launch after $reason"
    )
  }

  override suspend fun bringCellMapperToForeground(reason: String): PhoneAutomationActionResult {
    val target = resolvedTarget(PhoneAutomationApp.CELLMAPPER) ?: return missingTargetResult(PhoneAutomationApp.CELLMAPPER)
    val launch = launchTarget(target)
    return if (launch.success) {
      PhoneAutomationActionResult(true, "Returned CellMapper to the foreground after $reason")
    } else {
      launch
    }
  }

  override suspend fun bringOrchestratorToForeground(reason: String): PhoneAutomationActionResult {
    bridge.recordExpectedOrchestratorForeground()
    val launchIntent = runCatching {
      Intent(context, MainActivity::class.java).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
      }
    }.getOrElse {
      Intent(context, MainActivity::class.java)
    }
    return launchPackageToForeground(
      packageName = context.packageName,
      displayName = "Orchestrator",
      launchIntent = launchIntent,
      successDetail = "Returned the orchestrator to the foreground after $reason"
    )
  }

  private fun resolvedTarget(app: PhoneAutomationApp): PhoneAutomationResolvedTarget? {
    return resolvedTargets?.target(app)
  }

  private suspend fun runSpeedtestLaunch(
    target: PhoneAutomationResolvedTarget?,
    reason: String,
    plan: SpeedtestLaunchPlan
  ): SpeedtestLaunchOutcome {
    return try {
      val resolvedTarget = target ?: return SpeedtestLaunchOutcome(
        success = false,
        path = PhoneAutomationRestartPath.FAILED,
        detail = "Speedtest target is unavailable",
        failureMode = PhoneAutomationFailureMode.PERMANENT
      )
      logInfo("speedtest_launch_requested reason=$reason phase=${plan.initialPhase}")
      val launchResult = launchTarget(resolvedTarget)
      if (!launchResult.success) {
        return speedtestLaunchFailure(launchResult.detail)
      }

      when (
        val primaryAttempt = attemptSpeedtestRunProof(
          target = resolvedTarget,
          reason = reason,
          selectorAttempts = plan.primarySelectorAttempts,
          noMatchDetail = plan.primaryNoMatchDetail,
          isRelaunchAttempt = false
        )
      ) {
        is SpeedtestUiAttemptResult.Success -> {
          return SpeedtestLaunchOutcome(
            success = true,
            path = plan.primarySuccessPath,
            detail = plan.buildPrimarySuccessDetail(primaryAttempt.evidence)
          )
        }

        is SpeedtestUiAttemptResult.TransientFailure -> {
          if (
            primaryAttempt.failureRecovery != SpeedtestFailureRecovery.FORCE_STOP_RELAUNCH ||
            plan.relaunchSelectorAttempts.isEmpty()
          ) {
            return speedtestLaunchFailure(
              detail = primaryAttempt.detail,
              failureCategory = primaryAttempt.failureCategory,
              failureCleanupDisposition = primaryAttempt.failureCleanupDisposition,
              recoveryReasonKey = primaryAttempt.recoveryReasonKey
            )
          }
        }

        is SpeedtestUiAttemptResult.StableNoMatch -> Unit
      }

      if (plan.relaunchSelectorAttempts.isEmpty()) {
        return speedtestLaunchFailure(
          detail = plan.primaryNoMatchDetail,
          failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
          recoveryReasonKey = "unexpected_speedtest_screen"
        )
      }

      val forceStop = rootExecutor.run("am force-stop ${resolvedTarget.packageName}")
      if (!forceStop.ok) {
        return SpeedtestLaunchOutcome(
          success = false,
          path = PhoneAutomationRestartPath.FAILED,
          detail = forceStop.stderr.ifBlank { forceStop.stdout.ifBlank { plan.primaryNoMatchDetail } },
          failureMode = PhoneAutomationFailureMode.PERMANENT
        )
      }
      delay(FORCE_STOP_SETTLE_DELAY_MILLIS)
      logInfo("speedtest_launch_requested reason=$reason phase=relaunch")
      val relaunch = launchTarget(resolvedTarget)
      if (!relaunch.success) {
        return speedtestLaunchFailure(relaunch.detail)
      }

      when (
        val relaunchAttempt = attemptSpeedtestRunProof(
          target = resolvedTarget,
          reason = reason,
          selectorAttempts = plan.relaunchSelectorAttempts,
          noMatchDetail = plan.relaunchNoMatchDetail,
          isRelaunchAttempt = true
        )
      ) {
        is SpeedtestUiAttemptResult.Success -> {
          return SpeedtestLaunchOutcome(
            success = true,
            path = plan.relaunchSuccessPath,
            detail = plan.relaunchSuccessDetail
          )
        }

        is SpeedtestUiAttemptResult.TransientFailure -> {
          return speedtestLaunchFailure(
            detail = relaunchAttempt.detail,
            failureCategory = relaunchAttempt.failureCategory,
            failureCleanupDisposition = relaunchAttempt.failureCleanupDisposition,
            recoveryReasonKey = relaunchAttempt.recoveryReasonKey
          )
        }

        is SpeedtestUiAttemptResult.StableNoMatch -> {
          logWarn("unexpected_speedtest_screen reason=$reason phase=relaunch")
          return speedtestLaunchFailure(
            detail = "Speedtest showed an unexpected screen after relaunch",
            failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
            recoveryReasonKey = "unexpected_speedtest_screen"
          )
        }
      }
    } catch (_: CancellationException) {
      speedtestLaunchFailure(
        detail = "Speedtest launch was cancelled before proof completed",
        failureCategory = PhoneAutomationFailureCategory.CANCELLATION,
        failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST
      )
    }
  }

  private suspend fun attemptSpeedtestRunProof(
    target: PhoneAutomationResolvedTarget,
    reason: String,
    selectorAttempts: List<Pair<PhoneAutomationSelectorKind, String>>,
    noMatchDetail: String,
    isRelaunchAttempt: Boolean
  ): SpeedtestUiAttemptResult {
    for ((selectorKind, selectorDescription) in selectorAttempts) {
      var allowPassiveOrchestratorRefocus = true
      while (true) {
        when (
          val selectorAttempt = attemptSpeedtestSelectorClick(
            target = target,
            selectorKind = selectorKind,
            selectorDescription = selectorDescription,
            allowPassiveOrchestratorRefocus = allowPassiveOrchestratorRefocus
          )
        ) {
          is SpeedtestSelectorAttemptResult.Clicked -> {
            when (
              val runningProof = waitForSpeedtestRunningProof(
                target = target,
                reason = reason,
                selectorKind = selectorKind,
                selectorDescription = selectorDescription,
                isRelaunchAttempt = isRelaunchAttempt,
                allowPassiveOrchestratorRefocus = allowPassiveOrchestratorRefocus
              )
            ) {
              is SpeedtestRunningProofResult.Success -> {
                return SpeedtestUiAttemptResult.Success(runningProof.evidence)
              }

              is SpeedtestRunningProofResult.TransientFailure -> {
                return SpeedtestUiAttemptResult.TransientFailure(
                  detail = runningProof.detail,
                  failureCategory = runningProof.failureCategory,
                  failureCleanupDisposition = runningProof.failureCleanupDisposition,
                  failureRecovery = runningProof.failureRecovery,
                  recoveryReasonKey = runningProof.recoveryReasonKey,
                  evidence = runningProof.evidence
                )
              }
            }
          }

          is SpeedtestSelectorAttemptResult.RetryAfterFocusRecovery -> {
            allowPassiveOrchestratorRefocus = false
            continue
          }

          is SpeedtestSelectorAttemptResult.TransientFailure -> {
            return SpeedtestUiAttemptResult.TransientFailure(
              detail = selectorAttempt.detail,
              failureCategory = selectorAttempt.failureCategory,
              failureCleanupDisposition = selectorAttempt.failureCleanupDisposition
            )
          }

          is SpeedtestSelectorAttemptResult.StableMismatch -> break
        }
      }
    }
    return SpeedtestUiAttemptResult.StableNoMatch(noMatchDetail)
  }

  private suspend fun attemptSpeedtestSelectorClick(
    target: PhoneAutomationResolvedTarget,
    selectorKind: PhoneAutomationSelectorKind,
    selectorDescription: String,
    allowPassiveOrchestratorRefocus: Boolean
  ): SpeedtestSelectorAttemptResult {
    val clicked = bridge.clickSelectors(
      expectedPackageName = target.packageName,
      selectors = target.profile.selectors.getValue(selectorKind),
      timeoutMillis = PhoneAutomationSpeedtestTiming.UI_SELECTOR_TIMEOUT_MILLIS
    )
    if (clicked) {
      logInfo("speedtest_button_clicked selector=${selectorKind.name.lowercase()} package=${target.packageName}")
      return SpeedtestSelectorAttemptResult.Clicked
    }
    return classifySpeedtestSelectorFailure(
      target = target,
      selectorDescription = selectorDescription,
      allowPassiveOrchestratorRefocus = allowPassiveOrchestratorRefocus
    )
  }

  private suspend fun classifySpeedtestSelectorFailure(
    target: PhoneAutomationResolvedTarget,
    selectorDescription: String,
    allowPassiveOrchestratorRefocus: Boolean
  ): SpeedtestSelectorAttemptResult {
    if (waitForStableForeground(target.packageName, SELECTOR_STABILITY_WINDOW_MILLIS)) {
      return SpeedtestSelectorAttemptResult.StableMismatch(
        "Speedtest $selectorDescription selector did not match"
      )
    }
    return when (observedForegroundPackage()) {
      context.packageName -> {
        logWarn("speedtest_orchestrator_stole_focus stage=selector_wait selector=$selectorDescription")
        if (
          allowPassiveOrchestratorRefocus &&
          attemptPassiveSpeedtestRefocus(
            target = target,
            stage = "selector_wait",
            selectorDescription = selectorDescription
          )
        ) {
          return SpeedtestSelectorAttemptResult.RetryAfterFocusRecovery
        }
        SpeedtestSelectorAttemptResult.TransientFailure(
          detail = "Speedtest was interrupted when the orchestrator screen returned",
          failureCategory = PhoneAutomationFailureCategory.INTERRUPTION,
          failureCleanupDisposition =
            PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST_KEEP_CURRENT_FOREGROUND
        )
      }

      else -> {
        SpeedtestSelectorAttemptResult.TransientFailure("Speedtest launch did not stick")
      }
    }
  }

  private suspend fun waitForSpeedtestRunningProof(
    target: PhoneAutomationResolvedTarget,
    reason: String,
    selectorKind: PhoneAutomationSelectorKind,
    selectorDescription: String,
    isRelaunchAttempt: Boolean,
    allowPassiveOrchestratorRefocus: Boolean
  ): SpeedtestRunningProofResult {
    val runningMatcher = target.profile.notificationMatchers.getValue(
      PhoneAutomationNotificationKind.SPEEDTEST_RUNNING
    )
    val connectingSelectors = target.profile.selectors.getValue(
      PhoneAutomationSelectorKind.SPEEDTEST_CONNECTING
    )
    val attemptStartedAtMillis = clockMillis()
    val proofDeadline = attemptStartedAtMillis + PhoneAutomationSpeedtestTiming.RUNNING_PROOF_TIMEOUT_MILLIS
    var connectingDeadlineMillis: Long? = null
    var connectingSeen = false
    var connectingCleared = false
    var retryCleared = false
    var stableForegroundSince: Long? = null
    var canRecoverOrchestratorFocus = allowPassiveOrchestratorRefocus
    while (clockMillis() < maxOf(proofDeadline, connectingDeadlineMillis ?: 0L)) {
      if (hasFreshRunningNotification(
          matcher = runningMatcher,
          attemptStartedAtMillis = attemptStartedAtMillis
        )
      ) {
        logInfo("speedtest_running_notification_observed reason=$reason selector=$selectorDescription")
        return SpeedtestRunningProofResult.Success(
          SpeedtestLaunchEvidence(
            selectorDescription = selectorDescription,
            runningNotificationSeen = true,
            connectingSeen = connectingSeen,
            connectingCleared = connectingCleared
          )
        )
      }

      val now = clockMillis()
      val remainingWait = (maxOf(proofDeadline, connectingDeadlineMillis ?: 0L) - now).coerceAtLeast(1L)
      val posted = bridge.awaitNotificationPostedAfter(
        matcher = runningMatcher,
        observedAfterMillis = attemptStartedAtMillis,
        timeoutMillis = minOf(FOREGROUND_POLL_INTERVAL_MILLIS, remainingWait)
      )
      if (posted != null || hasFreshRunningNotification(
          matcher = runningMatcher,
          attemptStartedAtMillis = attemptStartedAtMillis
        )
      ) {
        logInfo("speedtest_running_notification_observed reason=$reason selector=$selectorDescription")
        return SpeedtestRunningProofResult.Success(
          SpeedtestLaunchEvidence(
            selectorDescription = selectorDescription,
            runningNotificationSeen = true,
            connectingSeen = connectingSeen,
            connectingCleared = connectingCleared
          )
        )
      }

      when (observedForegroundPackage()) {
        target.packageName -> {
          val connectingVisible = bridge.isSelectorPresent(
            expectedPackageName = target.packageName,
            selectors = connectingSelectors
          )
          val retryStillVisible = if (selectorKind == PhoneAutomationSelectorKind.SPEEDTEST_RETRY) {
            bridge.isSelectorPresent(
              expectedPackageName = target.packageName,
              selectors = target.profile.selectors.getValue(PhoneAutomationSelectorKind.SPEEDTEST_RETRY)
            )
          } else {
            false
          }
          if (connectingVisible) {
            if (!connectingSeen) {
              logInfo("speedtest_connecting_visible reason=$reason selector=$selectorDescription")
            }
            connectingSeen = true
            connectingDeadlineMillis = connectingDeadlineMillis ?: (
              clockMillis() + PhoneAutomationSpeedtestTiming.CONNECTING_TIMEOUT_MILLIS
            )
            stableForegroundSince = null
            if (clockMillis() >= checkNotNull(connectingDeadlineMillis)) {
              logWarn("speedtest_connecting_stuck reason=$reason selector=$selectorDescription relaunch=$isRelaunchAttempt")
              return stuckConnectingFailure(isRelaunchAttempt)
            }
          } else if (connectingSeen) {
            connectingCleared = true
            if (stableForegroundSince == null) {
              stableForegroundSince = clockMillis()
            } else if (clockMillis() - stableForegroundSince >=
              SPEEDTEST_FOREGROUND_STABILITY_PROOF_MILLIS
            ) {
              logInfo("speedtest_running_proof_fallback reason=$reason selector=$selectorDescription")
              return SpeedtestRunningProofResult.Success(
                SpeedtestLaunchEvidence(
                  selectorDescription = selectorDescription,
                  connectingSeen = true,
                  connectingCleared = true,
                  fallbackForegroundProof = true
                )
              )
            }
          } else if (selectorKind == PhoneAutomationSelectorKind.SPEEDTEST_RETRY && !retryStillVisible) {
            retryCleared = true
            if (stableForegroundSince == null) {
              stableForegroundSince = clockMillis()
            } else if (clockMillis() - stableForegroundSince >=
              SPEEDTEST_FOREGROUND_STABILITY_PROOF_MILLIS
            ) {
              logInfo("speedtest_running_proof_retry_cleared reason=$reason selector=$selectorDescription")
              return SpeedtestRunningProofResult.Success(
                SpeedtestLaunchEvidence(
                  selectorDescription = selectorDescription,
                  connectingSeen = connectingSeen,
                  connectingCleared = connectingCleared,
                  fallbackForegroundProof = true
                )
              )
            }
          } else {
            stableForegroundSince = null
          }
        }

        context.packageName -> {
          logWarn("speedtest_orchestrator_stole_focus stage=running_proof selector=$selectorDescription")
          if (
            canRecoverOrchestratorFocus &&
            attemptPassiveSpeedtestRefocus(
              target = target,
              stage = "running_proof",
              selectorDescription = selectorDescription
            )
          ) {
            canRecoverOrchestratorFocus = false
            stableForegroundSince = null
            continue
          }
          return SpeedtestRunningProofResult.TransientFailure(
            detail = "Speedtest was interrupted when the orchestrator screen returned",
            failureCategory = PhoneAutomationFailureCategory.INTERRUPTION,
            failureCleanupDisposition =
              PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST_KEEP_CURRENT_FOREGROUND,
            evidence = SpeedtestLaunchEvidence(
              selectorDescription = selectorDescription,
              connectingSeen = connectingSeen,
              connectingCleared = connectingCleared,
              fallbackForegroundProof = retryCleared,
              interruptedByOrchestrator = true
            )
          )
        }

        else -> {
          stableForegroundSince = null
        }
      }
    }

    if (hasFreshRunningNotification(
        matcher = runningMatcher,
        attemptStartedAtMillis = attemptStartedAtMillis
      )
    ) {
      logInfo("speedtest_running_notification_observed reason=$reason selector=$selectorDescription")
      return SpeedtestRunningProofResult.Success(
        SpeedtestLaunchEvidence(
          selectorDescription = selectorDescription,
          runningNotificationSeen = true,
          connectingSeen = connectingSeen,
          connectingCleared = connectingCleared
        )
      )
    }

    if (connectingSeen && !connectingCleared) {
      logWarn("speedtest_connecting_stuck reason=$reason selector=$selectorDescription relaunch=$isRelaunchAttempt")
      return stuckConnectingFailure(isRelaunchAttempt)
    }

    return SpeedtestRunningProofResult.TransientFailure(
      detail = "Speedtest launch did not stick",
      failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      evidence = SpeedtestLaunchEvidence(
        selectorDescription = selectorDescription,
        connectingSeen = connectingSeen,
        connectingCleared = connectingCleared,
        fallbackForegroundProof = retryCleared
      )
    )
  }

  private fun hasFreshRunningNotification(
    matcher: PhoneAutomationNotificationMatcher,
    attemptStartedAtMillis: Long
  ): Boolean {
    return bridge.matchingNotifications(matcher)
      .any { notification -> notification.postedAtMillis >= attemptStartedAtMillis }
  }

  private fun stuckConnectingFailure(isRelaunchAttempt: Boolean): SpeedtestRunningProofResult.TransientFailure {
    return if (isRelaunchAttempt) {
      SpeedtestRunningProofResult.TransientFailure(
        detail = "Speedtest relaunch also stayed on Connecting...",
        failureCategory = PhoneAutomationFailureCategory.STUCK_CONNECTING,
        failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST
      )
    } else {
      SpeedtestRunningProofResult.TransientFailure(
        detail = "Speedtest stayed on Connecting...",
        failureRecovery = SpeedtestFailureRecovery.FORCE_STOP_RELAUNCH
      )
    }
  }

  private fun speedtestLaunchFailure(
    detail: String,
    failureCategory: PhoneAutomationFailureCategory = PhoneAutomationFailureCategory.GENERIC,
    failureCleanupDisposition: PhoneAutomationFailureCleanupDisposition =
      PhoneAutomationFailureCleanupDisposition.NONE,
    recoveryReasonKey: String = ""
  ): SpeedtestLaunchOutcome {
    return SpeedtestLaunchOutcome(
      success = false,
      detail = detail,
      failureMode = PhoneAutomationFailureMode.TRANSIENT,
      failureCategory = failureCategory,
      failureCleanupDisposition = failureCleanupDisposition,
      retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR,
      recoveryReasonKey = recoveryReasonKey
    )
  }

  private suspend fun speedtestResultFingerprint(target: PhoneAutomationResolvedTarget): String {
    val nodes = bridge.snapshotVisibleNodes(target.packageName)
    if (nodes.isEmpty()) {
      return ""
    }
    return nodes.asSequence()
      .flatMap { node ->
        sequenceOf(
          node.text to node.resourceId,
          node.contentDescription to node.resourceId
        )
      }
      .map { (value, resourceId) -> value.trim() to resourceId }
      .filter { (value, resourceId) ->
        value.isNotBlank() &&
          resourceId != "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again" &&
          value != "Test Again" &&
          shouldRetainSpeedtestResultValue(value, resourceId)
      }
      .map { (value, resourceId) ->
        "${resourceId.ifBlank { "text" }}=${value.lowercase().replace(WHITESPACE_REGEX, " ")}"
      }
      .distinct()
      .sorted()
      .joinToString("|")
  }

  private fun shouldRetainSpeedtestResultValue(value: String, resourceId: String): Boolean {
    if (value.any(Char::isDigit)) {
      return true
    }
    val normalizedValue = value.lowercase()
    val normalizedResourceId = resourceId.lowercase()
    return RESULT_VALUE_KEYWORDS.any { keyword ->
      keyword in normalizedValue || keyword in normalizedResourceId
    }
  }

  private suspend fun launchTarget(target: PhoneAutomationResolvedTarget): PhoneAutomationActionResult {
    val launchIntent = runCatching {
      Intent(target.launchIntent).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      }
    }.getOrElse { target.launchIntent }
    return launchPackageToForeground(
      packageName = target.packageName,
      displayName = target.profile.displayName,
      launchIntent = launchIntent,
      successDetail = "Opened ${target.profile.displayName}",
      timeoutMillis = if (target.profile.app == PhoneAutomationApp.SPEEDTEST) {
        PhoneAutomationSpeedtestTiming.APP_FOREGROUND_TIMEOUT_MILLIS
      } else {
        DEFAULT_APP_LAUNCH_TIMEOUT_MILLIS
      }
    )
  }

  private suspend fun launchPackageToForeground(
    packageName: String,
    displayName: String,
    launchIntent: Intent,
    successDetail: String,
    timeoutMillis: Long = DEFAULT_APP_LAUNCH_TIMEOUT_MILLIS
  ): PhoneAutomationActionResult {
    return try {
      logInfo("app_launch_requested package=$packageName")
      context.startActivity(launchIntent)
      val foreground = waitForTargetForeground(packageName, timeoutMillis)
      if (foreground) {
        logInfo("app_foreground_reached package=$packageName")
        PhoneAutomationActionResult(true, successDetail)
      } else {
        PhoneAutomationActionResult(
          false,
          "$displayName did not reach the foreground",
          PhoneAutomationFailureMode.TRANSIENT
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      PhoneAutomationActionResult(
        success = false,
        detail = "$displayName launch failed: ${error.message ?: error::class.java.simpleName}",
        failureMode = PhoneAutomationFailureMode.TRANSIENT
      )
    }
  }

  private suspend fun waitForTargetForeground(packageName: String, timeoutMillis: Long): Boolean {
    if (bridge.isForegroundPackage(packageName) || currentFocusedPackage() == packageName) {
      return true
    }
    return bridge.waitForForegroundPackage(packageName, timeoutMillis) || currentFocusedPackage() == packageName
  }

  private suspend fun waitForStableForeground(packageName: String, timeoutMillis: Long): Boolean {
    val deadline = clockMillis() + timeoutMillis
    var stableSince: Long? = null
    while (clockMillis() < deadline) {
      if (observedForegroundPackage() != packageName) {
        return false
      }
      if (stableSince == null) {
        stableSince = clockMillis()
      }
      delay(FOREGROUND_POLL_INTERVAL_MILLIS)
    }
    return observedForegroundPackage() == packageName && stableSince != null
  }

  private suspend fun observedForegroundPackage(): String? {
    return bridge.currentForegroundPackage() ?: currentFocusedPackage()
  }

  private suspend fun currentFocusedPackage(): String? {
    val result = rootExecutor.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1")
    if (!result.ok) {
      return null
    }
    return FOCUSED_PACKAGE_PATTERN.find(result.stdout)?.groupValues?.get(1)
  }

  private suspend fun waitForNotification(
    matcher: PhoneAutomationNotificationMatcher,
    timeoutMillis: Long
  ): Boolean {
    val deadline = clockMillis() + timeoutMillis
    while (clockMillis() < deadline) {
      if (bridge.isNotificationPresent(matcher)) {
        return true
      }
      delay(FOREGROUND_POLL_INTERVAL_MILLIS)
    }
    return bridge.isNotificationPresent(matcher)
  }

  private suspend fun attemptPassiveSpeedtestRefocus(
    target: PhoneAutomationResolvedTarget,
    stage: String,
    selectorDescription: String
  ): Boolean {
    logInfo("speedtest_focus_recovery_requested stage=$stage selector=$selectorDescription")
    val relaunch = launchTarget(target)
    if (!relaunch.success) {
      logWarn(
        "speedtest_focus_recovery_failed stage=$stage selector=$selectorDescription detail=${relaunch.detail}"
      )
      return false
    }
    logInfo("speedtest_focus_recovery_succeeded stage=$stage selector=$selectorDescription")
    return true
  }

  private fun missingTargetResult(app: PhoneAutomationApp): PhoneAutomationActionResult {
    val name = PhoneAutomationProfiles.profile(app).displayName
    return PhoneAutomationActionResult(
      false,
      "$name is unavailable",
      PhoneAutomationFailureMode.PERMANENT
    )
  }

  private suspend fun attemptPermissionRepairIfNeeded() {
    val repaired = mutableListOf<String>()
    if (!bridge.isAccessibilityPermissionGranted(context) && repairAccessibilityPermission()) {
      repaired += "accessibility"
    }
    if (!bridge.isNotificationAccessPermissionGranted(context) && repairNotificationPermission()) {
      repaired += "notification"
    }
    if (repaired.isNotEmpty()) {
      delay(PERMISSION_REPAIR_SETTLE_DELAY_MILLIS)
      logInfo("permission_auto_repair repaired=${repaired.joinToString(",")}")
    }
  }

  private suspend fun repairAccessibilityPermission(): Boolean {
    val recovery = accessibilityRecovery.repairPermissionIfNeeded()
    if (recovery.recovered) {
      return true
    }
    logWarn("permission_auto_repair_failed permission=accessibility stage=${recovery.stage.name.lowercase()}")
    return false
  }

  private suspend fun attemptAccessibilityRebindIfNeeded(): PhoneAutomationAccessibilityRecoveryResult {
    val recovery = accessibilityRecovery.recoverDisconnectedService(
      timeoutMillis = SERVICE_CONNECTION_TIMEOUT_MILLIS
    )
    if (recovery.recovered) {
      Log.i(
        TAG,
        "permission_auto_repair repaired=accessibility_rebind stage=${recovery.stage.name.lowercase()}"
      )
    }
    return recovery
  }

  private suspend fun repairNotificationPermission(): Boolean {
    return runCatching {
      if (!ensureWriteSecureSettingsPermission()) {
        logWarn("permission_auto_repair_failed permission=notification reason=write_secure_settings_grant")
        return false
      }
      val component = ComponentName(context, PhoneAutomationNotificationListenerService::class.java)
      val updated = Settings.Secure.putString(
        context.contentResolver,
        "enabled_notification_listeners",
        PhoneAutomationServicePermissions.mergeEnabledServices(
          currentValue = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"),
          componentName = component
        )
      )
      if (!updated) {
        logWarn("permission_auto_repair_failed permission=notification")
      }
      updated
    }.getOrElse { error ->
      logWarn("permission_auto_repair_failed permission=notification error=${error.message ?: error::class.java.simpleName}")
      false
    }
  }

  private fun logInfo(message: String) {
    runCatching {
      Log.i(TAG, message)
    }
  }

  private fun logWarn(message: String) {
    runCatching {
      Log.w(TAG, message)
    }
  }

  private suspend fun ensureWriteSecureSettingsPermission(): Boolean {
    val result = rootExecutor.run("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
    return result.ok
  }

  companion object {
    private val FOCUSED_PACKAGE_PATTERN = Regex("""([A-Za-z0-9._]+)/(?:[A-Za-z0-9.${'$'}_]+)""")
    private const val TAG = "PhoneAutomation"
    private const val SERVICE_CONNECTION_TIMEOUT_MILLIS = 15_000L
    private val RESULT_VALUE_KEYWORDS = setOf(
      "download",
      "upload",
      "latency",
      "ping",
      "jitter",
      "packet",
      "loss",
      "idle",
      "mbps",
      "kbps",
      "ms"
    )
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val DEFAULT_APP_LAUNCH_TIMEOUT_MILLIS = 10_000L
    private const val FOREGROUND_POLL_INTERVAL_MILLIS = 250L
    private const val CELL_MAPPER_SELECTOR_TIMEOUT_MILLIS = 8_000L
    private const val CELL_MAPPER_NOTIFICATION_TIMEOUT_MILLIS = 8_000L
    private const val SPEEDTEST_FOREGROUND_STABILITY_PROOF_MILLIS = 2_000L
    private const val SELECTOR_STABILITY_WINDOW_MILLIS = 1_000L
    private const val FORCE_STOP_SETTLE_DELAY_MILLIS = 1_500L
    private const val PERMISSION_REPAIR_SETTLE_DELAY_MILLIS = 1_000L
  }
}
