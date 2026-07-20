package lv.jolkins.pixelorchestrator.app.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal class SpacetimeOrchestratorTelemetryConfig(
  val host: String,
  val database: String,
  private val bearerToken: String,
  val connectTimeoutMillis: Int = 10_000,
  val readTimeoutMillis: Int = 10_000
) {
  init {
    val uri = URI(host)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
      "host must be an HTTP or HTTPS origin"
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
      "host must not include credentials, a query, or a fragment"
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
      "host must be an origin without a path"
    }
    require(database.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))) {
      "database must be a bounded Spacetime database name"
    }
    require(bearerToken.isNotBlank()) { "bearerToken is required" }
    require(connectTimeoutMillis in 1..60_000) { "connect timeout is out of range" }
    require(readTimeoutMillis in 1..60_000) { "read timeout is out of range" }
  }

  internal fun authorizationHeader(): String = "Bearer $bearerToken"

  override fun toString(): String {
    return "SpacetimeOrchestratorTelemetryConfig(host=$host, database=$database, bearerToken=<redacted>)"
  }
}

internal class SpacetimeOrchestratorTelemetryTransport(
  private val config: SpacetimeOrchestratorTelemetryConfig
) : OrchestratorTelemetryTransport {
  override suspend fun send(
    payload: OrchestratorTelemetryPayload
  ): OrchestratorTelemetrySendResult = withContext(Dispatchers.IO) {
    val database = pathEscape(config.database)
    val endpoint =
      "${config.host.trimEnd('/')}/v1/database/$database/call/pixelorchestrator_append_event"
    val connection = try {
      URL(endpoint).openConnection() as HttpURLConnection
    } catch (_: IOException) {
      return@withContext OrchestratorTelemetrySendResult.Retryable()
    }
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = config.connectTimeoutMillis
      connection.readTimeout = config.readTimeoutMillis
      connection.doOutput = true
      connection.useCaches = false
      connection.setRequestProperty("Authorization", config.authorizationHeader())
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { output ->
        output.write(payload.reducerRequestBytes())
      }
      classifyHttpStatus(connection.responseCode)
    } catch (_: IOException) {
      OrchestratorTelemetrySendResult.Retryable()
    } finally {
      runCatching { connection.inputStream?.close() }
      runCatching { connection.errorStream?.close() }
      connection.disconnect()
    }
  }

  companion object {
    internal fun classifyHttpStatus(statusCode: Int): OrchestratorTelemetrySendResult {
      return when {
        statusCode in 200..299 -> OrchestratorTelemetrySendResult.Success
        statusCode in 500..599 || statusCode in setOf(408, 425, 429) ->
          OrchestratorTelemetrySendResult.Retryable(statusCode)
        else -> OrchestratorTelemetrySendResult.Rejected(statusCode)
      }
    }

    private fun pathEscape(value: String): String {
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
  }
}
