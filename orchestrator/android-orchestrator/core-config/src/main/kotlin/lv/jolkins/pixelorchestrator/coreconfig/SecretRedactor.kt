package lv.jolkins.pixelorchestrator.coreconfig

object SecretRedactor {
  fun redact(config: StackConfigV1, includeSecrets: Boolean): StackConfigV1 {
    if (includeSecrets) {
      return config
    }

    return config.copy(
      remote = config.remote.copy(
        dohPathToken = NativeStore.redactToken(config.remote.dohPathToken)
      )
    )
  }

}
