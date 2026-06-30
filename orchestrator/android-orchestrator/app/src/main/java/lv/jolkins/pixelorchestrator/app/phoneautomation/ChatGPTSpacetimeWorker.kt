package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class ChatGPTSpacetimeWorker(
  private val scope: CoroutineScope,
  private val rootExecutor: RootExecutor,
  private val runner: ChatGPTPhoneRunner,
  private val ticketPriorityActive: () -> Boolean,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
  private var workerJob: Job? = null

  fun start() {
    if (workerJob?.isActive == true) {
      return
    }
    workerJob = scope.launch(Dispatchers.IO) {
      workerLoop()
    }
  }

  fun stop() {
    workerJob?.cancel()
    workerJob = null
  }

  private suspend fun workerLoop() {
    while (true) {
      val config = loadConfig()
      if (config == null) {
        delay(CONFIG_RELOAD_MILLIS)
        continue
      }
      val client = ChatGPTSpacetimeAndroidClient(config, json)
      try {
        client.register()
        Log.i(TAG, "chatgpt_spacetime_worker_ready database=${config.database} worker=${config.workerId}")
        while (true) {
          runCycle(config, client)
          delay(config.pollMillis)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        Log.w(TAG, "chatgpt_spacetime_worker_error ${error.message ?: error::class.java.simpleName}")
        delay(ERROR_BACKOFF_MILLIS)
      }
    }
  }

  private suspend fun runCycle(config: ChatGPTSpacetimeConfig, client: ChatGPTSpacetimeAndroidClient) {
    if (ticketPriorityActive()) {
      client.heartbeat("waiting_ticket", details("reason" to "ticket_remote_active"))
      return
    }
    client.heartbeat("idle", details("queue" to "silent"))
    val attemptId = "${config.workerId}-${System.currentTimeMillis()}"
    client.call(
      reducer = "chatgptbroker_claim_next_job",
      args = listOf(config.workerId, config.workerId, attemptId)
    )
    val work = client.phoneWork()
      .firstOrNull { item ->
        item.status == "running" &&
          item.claimedBy == config.workerId &&
          item.activeAttemptId.isNotBlank()
      }
      ?: return
    if (work.cancelRequested) {
      client.markFailed(work, "CANCELLED", retryable = false, publicStatus = "Cancelled")
      return
    }
    if (ticketPriorityActive()) {
      client.markPreempted(work, "ticket_remote_active")
      return
    }
    client.heartbeat("running", details("jobId" to work.id))
    val projectName = config.projectName.ifBlank { work.projectName }
    val result = runner.runTextJobRootOnly(
      expectedProject = projectName,
      prompt = work.prompt,
      responseWaitMillis = config.responseWaitMillis
    )
    if (ticketPriorityActive()) {
      client.markPreempted(work, "ticket_remote_active")
      return
    }
    if (result.ok && result.screenshotPngBase64.isNotBlank()) {
      client.call(
        reducer = "chatgptbroker_mark_screenshot_ready",
        args = listOf(work.id, work.activeAttemptId, result.screenshotPngBase64)
      )
      client.heartbeat("ocr_pending", details("jobId" to work.id))
      return
    }
    Log.w(
      TAG,
      "chatgpt_job_failed job=${work.id} attempt=${work.activeAttemptId} stage=${result.stage} code=${result.failureCode} detail=${result.detail}"
    )
    client.markFailed(
      work = work,
      failureCode = result.failureCode?.name ?: "UNKNOWN",
      retryable = false,
      publicStatus = if (result.failureCode == ChatGPTPhoneFailureCode.PROJECT_NOT_VERIFIED) {
        "Project verification failed"
      } else {
        "Phone automation failed"
      }
    )
  }

  private suspend fun loadConfig(): ChatGPTSpacetimeConfig? {
    val result = rootExecutor.runScript(
      """
        if [ -f "$CONFIG_PATH" ]; then
          cat "$CONFIG_PATH"
        fi
      """.trimIndent(),
      3.seconds
    )
    if (!result.ok || result.stdout.isBlank()) {
      return null
    }
    val env = parseEnv(result.stdout)
    if (env["CHATGPT_PIXEL_WORKER_ENABLED"]?.isFalseLike() == true) {
      return null
    }
    val database = env["CHATGPT_SPACETIME_DATABASE"]?.trim().orEmpty()
    val token = env["CHATGPT_SPACETIME_PHONE_TOKEN"]?.trim().orEmpty()
      .ifBlank { env["CHATGPT_SPACETIME_BEARER_TOKEN"]?.trim().orEmpty() }
    if (database.isBlank() || token.isBlank()) {
      return null
    }
    return ChatGPTSpacetimeConfig(
      host = env["CHATGPT_SPACETIME_HOST"]?.trim().orEmpty().ifBlank { DEFAULT_SPACETIME_HOST }.trimEnd('/'),
      database = database,
      bearerToken = token,
      workerId = env["CHATGPT_PIXEL_WORKER_ID"]?.trim().orEmpty().ifBlank { DEFAULT_WORKER_ID },
      projectName = env["CHATGPT_PROJECT_NAME"]?.trim().orEmpty(),
      pollMillis = env["CHATGPT_PIXEL_POLL_MILLIS"]?.toLongOrNull()?.coerceIn(500L, 30_000L)
        ?: DEFAULT_POLL_MILLIS,
      responseWaitMillis = env["CHATGPT_PIXEL_RESPONSE_WAIT_MILLIS"]?.toLongOrNull()?.coerceIn(10_000L, 300_000L)
        ?: DEFAULT_RESPONSE_WAIT_MILLIS,
      httpTimeoutMillis = env["CHATGPT_PIXEL_HTTP_TIMEOUT_MILLIS"]?.toIntOrNull()?.coerceIn(2_000, 60_000)
        ?: DEFAULT_HTTP_TIMEOUT_MILLIS
    )
  }

  private fun parseEnv(text: String): Map<String, String> {
    return text
      .lineSequence()
      .map { line -> line.trim() }
      .filter { line -> line.isNotBlank() && !line.startsWith("#") }
      .map { line -> line.removePrefix("export ").trim() }
      .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) {
          null
        } else {
          line.substring(0, separator).trim() to parseEnvValue(line.substring(separator + 1).trim())
        }
      }
      .toMap()
  }

  private fun parseEnvValue(raw: String): String {
    if (raw.length >= 2) {
      val first = raw.first()
      val last = raw.last()
      if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
        return raw.substring(1, raw.length - 1)
      }
    }
    return raw
  }

  private fun String.isFalseLike(): Boolean {
    return trim().lowercase() in setOf("0", "false", "no", "off", "disabled")
  }

  private fun details(vararg pairs: Pair<String, String>): String {
    return buildJsonObject {
      pairs.forEach { (key, value) -> put(key, value.take(160)) }
    }.toString()
  }

  private companion object {
    const val TAG = "ChatGPTSpacetimeWorker"
    const val DEFAULT_SPACETIME_HOST = "https://maincloud.spacetimedb.com"
    const val DEFAULT_WORKER_ID = "pixel-chatgpt-phone"
    const val DEFAULT_POLL_MILLIS = 2_000L
    const val DEFAULT_RESPONSE_WAIT_MILLIS = 90_000L
    const val DEFAULT_HTTP_TIMEOUT_MILLIS = 20_000
    const val CONFIG_RELOAD_MILLIS = 30_000L
    const val ERROR_BACKOFF_MILLIS = 5_000L
    const val CONFIG_PATH = "/data/local/pixel-stack/conf/apps/chatgpt-broker.env"
  }
}

private data class ChatGPTSpacetimeConfig(
  val host: String,
  val database: String,
  val bearerToken: String,
  val workerId: String,
  val projectName: String,
  val pollMillis: Long,
  val responseWaitMillis: Long,
  val httpTimeoutMillis: Int
)

private data class ChatGPTPhoneWork(
  val id: String,
  val status: String,
  val projectName: String,
  val prompt: String,
  val activeAttemptId: String,
  val claimedBy: String,
  val cancelRequested: Boolean
)

private class ChatGPTSpacetimeAndroidClient(
  private val config: ChatGPTSpacetimeConfig,
  private val json: Json
) {
  suspend fun register() {
    call("chatgptbroker_register_service_identity", listOf(config.workerId, "phone"))
  }

  suspend fun heartbeat(status: String, safeDetailsJson: String) {
    call(
      reducer = "chatgptbroker_phone_heartbeat",
      args = listOf(config.workerId, config.workerId, status, safeDetailsJson)
    )
  }

  suspend fun phoneWork(): List<ChatGPTPhoneWork> {
    return query("SELECT * FROM chatgptbroker_phone_work")
      .map { row ->
        ChatGPTPhoneWork(
          id = row.string("id"),
          status = row.string("status"),
          projectName = row.string("projectName"),
          prompt = row.string("prompt"),
          activeAttemptId = row.string("activeAttemptId"),
          claimedBy = row.string("claimedBy"),
          cancelRequested = row.boolean("cancelRequested")
        )
      }
  }

  suspend fun markPreempted(work: ChatGPTPhoneWork, reason: String) {
    call(
      reducer = "chatgptbroker_mark_preempted",
      args = listOf(work.id, work.activeAttemptId, reason)
    )
  }

  suspend fun markFailed(
    work: ChatGPTPhoneWork,
    failureCode: String,
    retryable: Boolean,
    publicStatus: String
  ) {
    call(
      reducer = "chatgptbroker_mark_failed",
      args = listOf(work.id, work.activeAttemptId, failureCode, retryable, publicStatus)
    )
  }

  suspend fun call(reducer: String, args: List<Any>) {
    val path = "/v1/database/${pathEscape(config.database)}/call/${pathEscape(reducer)}"
    val body = JsonArray(args.map(::jsonArg)).toString()
    post(path, "application/json", body)
  }

  private suspend fun query(sql: String): List<Map<String, JsonElement>> {
    val body = post(
      path = "/v1/database/${pathEscape(config.database)}/sql",
      contentType = "text/plain; charset=utf-8",
      body = sql
    )
    if (body.isBlank()) {
      return emptyList()
    }
    return decodeSQLRows(body)
  }

  private fun post(path: String, contentType: String, body: String): String {
    val connection = (URL("${config.host}$path").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = config.httpTimeoutMillis
      readTimeout = config.httpTimeoutMillis
      doOutput = true
      useCaches = false
      setRequestProperty("Authorization", "Bearer ${config.bearerToken}")
      setRequestProperty("Content-Type", contentType)
    }
    connection.outputStream.use { output ->
      output.write(body.toByteArray(Charsets.UTF_8))
    }
    val code = connection.responseCode
    val response = runCatching {
      val input = if (code in 200..299) connection.inputStream else connection.errorStream
      input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }.getOrDefault("")
    connection.disconnect()
    if (code !in 200..299) {
      error("SpacetimeDB call failed with HTTP $code: ${response.take(240)}")
    }
    return response
  }

  private fun decodeSQLRows(body: String): List<Map<String, JsonElement>> {
    val statements = json.parseToJsonElement(body).jsonArray
    val out = mutableListOf<Map<String, JsonElement>>()
    statements.forEach { statement ->
      val statementObject = statement.jsonObject
      val fields = schemaFields(statementObject["schema"] ?: JsonNull)
      val rows = statementObject["rows"]?.jsonArray ?: JsonArray(emptyList())
      rows.forEach { row ->
        out.add(rowMap(fields, row))
      }
    }
    return out
  }

  private fun schemaFields(schema: JsonElement): List<String> {
    val elements = findElements(schema) ?: return emptyList()
    return elements.mapIndexed { index, element ->
      unwrap(element.jsonObject["name"])?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?: "field$index"
    }
  }

  private fun findElements(element: JsonElement): JsonArray? {
    return when (element) {
      is JsonObject -> {
        element["elements"]?.jsonArray ?: element.values.firstNotNullOfOrNull(::findElements)
      }
      is JsonArray -> element.firstNotNullOfOrNull(::findElements)
      else -> null
    }
  }

  private fun rowMap(fields: List<String>, row: JsonElement): Map<String, JsonElement> {
    return when (row) {
      is JsonObject -> row.mapValues { (_, value) -> unwrap(value) ?: JsonNull }
      is JsonArray -> row.mapIndexed { index, value ->
        val key = fields.getOrNull(index) ?: "field$index"
        key to (unwrap(value) ?: JsonNull)
      }.toMap()
      else -> mapOf("field0" to (unwrap(row) ?: JsonNull))
    }
  }

  private fun unwrap(value: JsonElement?): JsonElement? {
    return when (value) {
      null -> null
      is JsonObject -> {
        if (value.size == 1) {
          val entry = value.entries.first()
          when (entry.key.lowercase()) {
            "some", "string", "i64", "u64", "i32", "u32", "bool", "timestamp" -> unwrap(entry.value)
            "none" -> JsonNull
            else -> value
          }
        } else {
          value
        }
      }
      else -> value
    }
  }

  private fun Map<String, JsonElement>.string(key: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
  }

  private fun Map<String, JsonElement>.boolean(key: String): Boolean {
    return this[key]?.jsonPrimitive?.booleanOrNull ?: false
  }

  private fun jsonArg(value: Any): JsonElement {
    return when (value) {
      is Boolean -> JsonPrimitive(value)
      is Int -> JsonPrimitive(value)
      is Long -> JsonPrimitive(value)
      is String -> JsonPrimitive(value)
      else -> JsonNull
    }
  }

  private fun pathEscape(value: String): String {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  }
}
