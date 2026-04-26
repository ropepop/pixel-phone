package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ComponentName

internal data class PhoneAutomationServiceComponent(
  val fullName: String,
  val shortName: String = fullName
)

internal object PhoneAutomationServicePermissions {
  fun mergeEnabledServices(currentValue: String?, componentName: String): String {
    val merged = normalizeEnabledServices(currentValue)
    merged += componentName
    return merged.joinToString(":")
  }

  fun mergeEnabledServices(currentValue: String?, componentName: PhoneAutomationServiceComponent): String {
    val merged = normalizeEnabledServices(currentValue)
    if (!containsAnyComponentName(merged, componentName)) {
      merged += componentName.fullName
    }
    return merged.joinToString(":")
  }

  fun mergeEnabledServices(currentValue: String?, componentName: ComponentName): String {
    return mergeEnabledServices(currentValue, serviceComponent(componentName))
  }

  fun removeEnabledService(currentValue: String?, componentName: PhoneAutomationServiceComponent): String {
    return normalizeEnabledServices(currentValue)
      .filterNot { candidate -> isMatchingComponentName(candidate, componentName) }
      .joinToString(":")
  }

  fun removeEnabledService(currentValue: String?, componentName: ComponentName): String {
    return removeEnabledService(currentValue, serviceComponent(componentName))
  }

  fun containsEnabledService(currentValue: String?, componentName: PhoneAutomationServiceComponent): Boolean {
    return containsAnyComponentName(normalizeEnabledServices(currentValue), componentName)
  }

  fun containsEnabledService(currentValue: String?, componentName: ComponentName): Boolean {
    return containsEnabledService(currentValue, serviceComponent(componentName))
  }

  private fun normalizeEnabledServices(currentValue: String?): LinkedHashSet<String> {
    val normalized = linkedSetOf<String>()
    currentValue
      ?.split(':')
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
      ?.forEach { normalized += it }
    return normalized
  }

  private fun containsAnyComponentName(
    values: Collection<String>,
    componentName: PhoneAutomationServiceComponent
  ): Boolean {
    return values.any { candidate -> isMatchingComponentName(candidate, componentName) }
  }

  private fun isMatchingComponentName(candidate: String, componentName: PhoneAutomationServiceComponent): Boolean {
    return candidate == componentName.fullName || candidate == componentName.shortName
  }

  private fun serviceComponent(componentName: ComponentName): PhoneAutomationServiceComponent {
    return PhoneAutomationServiceComponent(
      fullName = componentName.flattenToString(),
      shortName = componentName.flattenToShortString()
    )
  }
}
