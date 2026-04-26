package lv.jolkins.pixelorchestrator.coreconfig

import kotlin.test.assertEquals
import org.junit.Test

class SecretRedactorTest {

  @Test
  fun redactsSecretFieldsWhenIncludeSecretsFalse() {
    val config = StackConfigV1(
      remote = RemoteConfig(
        dohPathToken = "tok123456789"
      )
    )

    val redacted = SecretRedactor.redact(config, includeSecrets = false)

    assertEquals("***redacted***", redacted.remote.dohPathToken)
  }

  @Test
  fun preservesSecretsWhenIncludeSecretsTrue() {
    val config = StackConfigV1(
      remote = RemoteConfig(dohPathToken = "tok123")
    )

    val unredacted = SecretRedactor.redact(config, includeSecrets = true)

    assertEquals("tok123", unredacted.remote.dohPathToken)
  }
}
