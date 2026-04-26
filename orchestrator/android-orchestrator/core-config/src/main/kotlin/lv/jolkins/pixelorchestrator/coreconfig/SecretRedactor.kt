package lv.jolkins.pixelorchestrator.coreconfig

object SecretRedactor {
  private const val MASK = "***redacted***"

  fun redact(config: StackConfigV1, includeSecrets: Boolean): StackConfigV1 {
    if (includeSecrets) {
      return config
    }

    return config.copy(
      remote = config.remote.copy(
        dohPathToken = redactIfSet(config.remote.dohPathToken)
      )
    )
  }

  private fun redactIfSet(value: String): String {
    return if (value.isBlank()) value else MASK
  }
}
