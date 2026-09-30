package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper

class ArtifactSyncer(
  private val cacheDir: Path,
  private val rootExecutor: RootExecutor? = null
) {

  init {
    Files.createDirectories(cacheDir)
  }

  fun sync(entry: ArtifactEntry): Path {
    val target = cacheDir.resolve(entry.fileName)
    if (Files.exists(target)) {
      val expectedSha = NativeArtifact.expectedSha(entry.sha256)
      if (NativeArtifact.cacheUsable(expectedSha, if (expectedSha.isEmpty()) "" else sha256(target))) {
        return target
      }
      Files.deleteIfExists(target)
    }

    val source = resolveLocalSource(entry.url, entry.id)
    copyFromSource(source = source, target = target, artifactId = entry.id)

    if (!Files.exists(target)) {
      error("Artifact sync failed for ${entry.id}: target file missing after copy")
    }

    val expectedSha = NativeArtifact.expectedSha(entry.sha256)
    if (expectedSha.isNotEmpty()) {
      val actualSha = sha256(target)
      if (actualSha != expectedSha) {
        Files.deleteIfExists(target)
        error("Artifact sync failed for ${entry.id}: sha256 mismatch (expected=$expectedSha actual=$actualSha)")
      }
    }

    return target
  }

  fun release(path: Path): Boolean {
    val normalizedCache = cacheDir.toAbsolutePath().normalize()
    val normalizedPath = path.toAbsolutePath().normalize()
    if (!NativeArtifact.releaseAllowed(normalizedPath.parent?.toString(), normalizedCache.toString())) {
      return false
    }
    return runCatching { Files.deleteIfExists(normalizedPath) }.getOrDefault(false)
  }

  fun sha256(path: Path): String = NativeArtifact.sha256(path.toString())

  private fun resolveLocalSource(rawUrl: String, artifactId: String): Path {
    val decision = NativeArtifact.call("source", buildJsonObject {put("url", rawUrl); put("id", artifactId)}).jsonObject
    val error = decision.getValue("error").jsonPrimitive.contentOrNull
    check(error == null) {error.orEmpty()}
    return Path.of(decision.getValue("path").jsonPrimitive.content)
  }

  private fun copyFromSource(source: Path, target: Path, artifactId: String) {
    Files.createDirectories(target.parent)
    Files.deleteIfExists(target)
    runCatching {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }.getOrElse { copyError ->
      rootCopyFromSource(source = source, target = target, artifactId = artifactId, copyError = copyError)
    }
  }

  private fun rootCopyFromSource(source: Path, target: Path, artifactId: String, copyError: Throwable) {
    val executor = rootExecutor
      ?: error(
        "Artifact sync failed for $artifactId from $source: ${copyError.message}. " +
          "Root executor unavailable for fallback copy."
      )

    val sourcePath = source.toAbsolutePath().toString()
    val targetPath = target.toAbsolutePath().toString()
    val script = """
      set -eu
      src=${ShellEscaper.singleQuote(sourcePath)}
      dst=${ShellEscaper.singleQuote(targetPath)}
      if [ ! -f "${'$'}src" ]; then
        echo "missing artifact source: ${'$'}src" >&2
        exit 41
      fi
      mkdir -p ${ShellEscaper.singleQuote(target.parent.toString())}
      cp "${'$'}src" "${'$'}dst"
      chmod 0644 "${'$'}dst" 2>/dev/null || true
    """.trimIndent()
    val result = runBlocking { executor.runScript(script) }
    if (!result.ok) {
      Files.deleteIfExists(target)
      error(
        "Artifact sync failed for $artifactId from $sourcePath. " +
          "copyError=${copyError.message}; rootFallback=${result.stderr}"
      )
    }
  }

}

internal object NativeArtifact {
  private val json = Json
  init {System.loadLibrary("pixel_health")}
  private external fun decide(operation: String, payload: String): String
  external fun sha256(path: String): String
  fun call(operation: String, args: JsonObject): JsonElement = json.parseToJsonElement(decide(operation, args.toString()))
  fun expectedSha(sha: String): String = call("expected_sha", buildJsonObject {put("sha", sha)}).jsonPrimitive.content
  fun cacheUsable(expected: String, actual: String): Boolean = call("cache_usable", buildJsonObject {put("expected", expected); put("actual", actual)}).jsonPrimitive.boolean
  fun releaseAllowed(parent: String?, cache: String): Boolean = call("release_allowed", buildJsonObject {put("parent", parent); put("cache", cache)}).jsonPrimitive.boolean
}
