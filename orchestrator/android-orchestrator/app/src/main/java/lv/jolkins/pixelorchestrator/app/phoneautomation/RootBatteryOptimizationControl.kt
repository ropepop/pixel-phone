package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlin.time.Duration.Companion.seconds
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper

internal data class RootBatteryWhitelistResult(
  val attempted: Boolean,
  val confirmed: Boolean,
  val detail: String
)

internal object RootBatteryOptimizationControl {
  fun batteryUnrestricted(
    androidBatteryUnrestricted: Boolean,
    rootWhitelistConfirmed: Boolean
  ): Boolean {
    return androidBatteryUnrestricted || rootWhitelistConfirmed
  }

  suspend fun ensureWhitelisted(
    packageName: String,
    rootExecutor: RootExecutor
  ): RootBatteryWhitelistResult {
    val packageArg = ShellEscaper.singleQuote("+$packageName")
    val addResult = runCatching {
      rootExecutor.run("cmd deviceidle whitelist $packageArg", timeout = 5.seconds)
    }.getOrElse { throwable ->
      return RootBatteryWhitelistResult(
        attempted = true,
        confirmed = false,
        detail = throwable.message ?: "Battery whitelist command failed"
      )
    }

    val readResult = runCatching {
      rootExecutor.run("cmd deviceidle whitelist", timeout = 5.seconds)
    }.getOrElse { throwable ->
      return RootBatteryWhitelistResult(
        attempted = true,
        confirmed = false,
        detail = throwable.message ?: "Battery whitelist verification failed"
      )
    }

    val confirmed = readResult.ok && isWhitelisted(readResult.stdout, packageName)
    return RootBatteryWhitelistResult(
      attempted = true,
      confirmed = confirmed,
      detail = when {
        confirmed -> "Root battery whitelist active"
        !addResult.ok -> addResult.stderr.ifBlank { addResult.stdout.ifBlank { "Battery whitelist command failed" } }
        !readResult.ok -> readResult.stderr.ifBlank { readResult.stdout.ifBlank { "Battery whitelist verification failed" } }
        else -> "Battery whitelist verification did not include $packageName"
      }
    )
  }

  fun isWhitelisted(whitelistOutput: String, packageName: String): Boolean {
    return whitelistOutput
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .any { line ->
        val columns = line.split(",").map(String::trim)
        columns.getOrNull(1) == packageName || line == packageName
      }
  }
}
