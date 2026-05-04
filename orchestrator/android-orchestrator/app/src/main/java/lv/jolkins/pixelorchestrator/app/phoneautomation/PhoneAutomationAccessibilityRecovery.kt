package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal enum class PhoneAutomationAccessibilityRecoveryStage {
  NONE,
  PERMISSION_REPAIRED,
  SETTINGS_REAPPLIED,
  SERVICE_REBOUND,
  FAILED
}

internal data class PhoneAutomationAccessibilityRecoveryResult(
  val recovered: Boolean,
  val stage: PhoneAutomationAccessibilityRecoveryStage
)

internal interface PhoneAutomationAccessibilityRecoveryEnvironment {
  val componentName: PhoneAutomationServiceComponent

  fun isPermissionGranted(): Boolean
  fun isConnected(): Boolean
  suspend fun awaitConnection(timeoutMillis: Long): Boolean
  suspend fun ensureWriteSecureSettingsPermission(): Boolean
  suspend fun allowRestrictedSettings(): Boolean
  fun isAccessibilityGloballyEnabled(): Boolean
  fun readEnabledAccessibilityServices(): String?
  fun writeEnabledAccessibilityServices(value: String): Boolean
  fun setAccessibilityGloballyEnabled(enabled: Boolean): Boolean
}

internal class AndroidPhoneAutomationAccessibilityRecoveryEnvironment(
  private val context: Context,
  private val rootExecutor: RootExecutor,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge
) : PhoneAutomationAccessibilityRecoveryEnvironment {
  override val componentName: PhoneAutomationServiceComponent =
    PhoneAutomationServiceComponent(
      fullName = "${context.packageName}/${PhoneAutomationAccessibilityService::class.java.name}",
      shortName = "${context.packageName}/.${PhoneAutomationAccessibilityService::class.java.simpleName}"
    )

  override fun isPermissionGranted(): Boolean = bridge.isAccessibilityPermissionGranted(context)

  override fun isConnected(): Boolean = bridge.isAccessibilityServiceConnected()

  override suspend fun awaitConnection(timeoutMillis: Long): Boolean {
    return bridge.awaitAccessibilityConnection(timeoutMillis)
  }

  override suspend fun ensureWriteSecureSettingsPermission(): Boolean {
    val result = rootExecutor.run("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
    return result.ok
  }

  override suspend fun allowRestrictedSettings(): Boolean {
    val result = rootExecutor.run("cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
    return result.ok
  }

  override fun isAccessibilityGloballyEnabled(): Boolean {
    return runCatching {
      Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED
      ) == 1
    }.getOrDefault(false)
  }

  override fun readEnabledAccessibilityServices(): String? {
    return Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
  }

  override fun writeEnabledAccessibilityServices(value: String): Boolean {
    return Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      value
    )
  }

  override fun setAccessibilityGloballyEnabled(enabled: Boolean): Boolean {
    return Settings.Secure.putInt(
      context.contentResolver,
      Settings.Secure.ACCESSIBILITY_ENABLED,
      if (enabled) 1 else 0
    )
  }
}

internal class PhoneAutomationAccessibilityRecovery(
  private val environment: PhoneAutomationAccessibilityRecoveryEnvironment,
  private val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
  private val rebindDelayMillis: Long = DEFAULT_REBIND_DELAY_MILLIS
) {
  suspend fun repairPermissionIfNeeded(): PhoneAutomationAccessibilityRecoveryResult {
    if (environment.isPermissionGranted()) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.NONE
      )
    }

    val applied = applyAccessibilitySettings(enableGlobalAccessibility = true)
    if (!applied) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.FAILED
      )
    }

    delay(settleDelayMillis)
    return PhoneAutomationAccessibilityRecoveryResult(
      recovered = environment.isPermissionGranted(),
      stage = if (environment.isPermissionGranted()) {
        PhoneAutomationAccessibilityRecoveryStage.PERMISSION_REPAIRED
      } else {
        PhoneAutomationAccessibilityRecoveryStage.FAILED
      }
    )
  }

  suspend fun recoverDisconnectedService(timeoutMillis: Long): PhoneAutomationAccessibilityRecoveryResult {
    if (environment.isConnected()) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.NONE
      )
    }
    if (!environment.isPermissionGranted()) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.FAILED
      )
    }

    val reapplied = applyAccessibilitySettings(enableGlobalAccessibility = false)
    if (reapplied) {
      delay(settleDelayMillis)
      if (environment.awaitConnection(timeoutMillis)) {
        return PhoneAutomationAccessibilityRecoveryResult(
          recovered = true,
          stage = PhoneAutomationAccessibilityRecoveryStage.SETTINGS_REAPPLIED
        )
      }
    }

    val currentValue = environment.readEnabledAccessibilityServices()
    val removedValue = PhoneAutomationServicePermissions.removeEnabledService(
      currentValue = currentValue,
      componentName = environment.componentName
    )
    if (!writeEnabledServices(removedValue)) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.FAILED
      )
    }

    delay(rebindDelayMillis)

    val reboundValue = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = removedValue,
      componentName = environment.componentName
    )
    if (!writeEnabledServices(reboundValue)) {
      return PhoneAutomationAccessibilityRecoveryResult(
        recovered = false,
        stage = PhoneAutomationAccessibilityRecoveryStage.FAILED
      )
    }

    delay(settleDelayMillis)
    val rebound = environment.awaitConnection(timeoutMillis)
    return PhoneAutomationAccessibilityRecoveryResult(
      recovered = rebound,
      stage = if (rebound) {
        PhoneAutomationAccessibilityRecoveryStage.SERVICE_REBOUND
      } else {
        PhoneAutomationAccessibilityRecoveryStage.FAILED
      }
    )
  }

  private suspend fun applyAccessibilitySettings(enableGlobalAccessibility: Boolean): Boolean {
    if (!environment.ensureWriteSecureSettingsPermission()) {
      return false
    }
    if (!environment.allowRestrictedSettings()) {
      return false
    }
    val mergedValue = PhoneAutomationServicePermissions.mergeEnabledServices(
      currentValue = environment.readEnabledAccessibilityServices(),
      componentName = environment.componentName
    )
    val servicesUpdated = writeEnabledServices(mergedValue)
    val globalUpdated = if (!enableGlobalAccessibility || environment.isAccessibilityGloballyEnabled()) {
      true
    } else {
      environment.setAccessibilityGloballyEnabled(enabled = true)
    }
    return servicesUpdated && globalUpdated
  }

  private suspend fun writeEnabledServices(value: String): Boolean {
    if (!environment.ensureWriteSecureSettingsPermission()) {
      return false
    }
    if (!environment.allowRestrictedSettings()) {
      return false
    }
    return environment.writeEnabledAccessibilityServices(value)
  }

  companion object {
    private const val DEFAULT_SETTLE_DELAY_MILLIS = 1_000L
    private const val DEFAULT_REBIND_DELAY_MILLIS = 500L
  }
}
