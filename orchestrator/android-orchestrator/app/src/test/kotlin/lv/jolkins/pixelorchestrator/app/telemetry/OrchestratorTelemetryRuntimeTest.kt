package lv.jolkins.pixelorchestrator.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrchestratorTelemetryRuntimeTest {
  @Test
  fun parseEnvKeepsOnlyTheThreeTypedConfigurationKeys() {
    val parsed = OrchestratorTelemetryRuntime.parseEnv(
      """
        OPERATIONAL_LOGGING_HOST="https://maincloud.spacetimedb.com"
        OPERATIONAL_LOGGING_DATABASE=operational-logging-prod
        OPERATIONAL_LOGGING_SERVICE_TOKEN_FILE='/data/local/pixel-stack/conf/apps/operational-logging-token'
        UNRELATED_SECRET=must-not-be-retained
      """.trimIndent()
    )

    assertEquals(
      "https://maincloud.spacetimedb.com",
      parsed[OrchestratorTelemetryRuntime.KEY_HOST]
    )
    assertEquals(
      "operational-logging-prod",
      parsed[OrchestratorTelemetryRuntime.KEY_DATABASE]
    )
    assertEquals(
      OrchestratorTelemetryRuntime.TOKEN_PATH,
      parsed[OrchestratorTelemetryRuntime.KEY_TOKEN_FILE]
    )
    assertFalse(parsed.containsKey("UNRELATED_SECRET"))
  }
}
