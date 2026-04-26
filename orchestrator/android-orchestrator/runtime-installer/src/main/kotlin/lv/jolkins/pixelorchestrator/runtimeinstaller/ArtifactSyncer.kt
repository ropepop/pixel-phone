package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
      val expectedSha = entry.sha256.trim().lowercase()
      if (expectedSha.isEmpty() || sha256(target) == expectedSha) {
        return target
      }
      Files.deleteIfExists(target)
    }

    val source = resolveLocalSource(entry.url, entry.id)
    copyFromSource(source = source, target = target, artifactId = entry.id)

    if (!Files.exists(target)) {
      error("Artifact sync failed for ${entry.id}: target file missing after copy")
    }

    val expectedSha = entry.sha256.trim().lowercase()
    if (expectedSha.isNotEmpty()) {
      val actualSha = sha256(target)
      if (actualSha != expectedSha) {
        Files.deleteIfExists(target)
        error("Artifact sync failed for ${entry.id}: sha256 mismatch (expected=$expectedSha actual=$actualSha)")
      }
    }

    return target
  }

  fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
      stream.copyToDigest(digest)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun resolveLocalSource(rawUrl: String, artifactId: String): Path {
    val url = rawUrl.trim()
    if (url.isBlank()) {
      error("Artifact source url is blank for $artifactId")
    }
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
      error("Remote artifact source is not allowed for $artifactId: $url")
    }

    val sourcePath = if (url.regionMatches(0, "file://", 0, 7, ignoreCase = true)) {
      url.substring(7)
    } else {
      url
    }

    if (!sourcePath.startsWith("/")) {
      error("Artifact source must be an absolute local path for $artifactId: $url")
    }

    return Path.of(sourcePath)
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

  private fun InputStream.copyToDigest(digest: MessageDigest) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = read(buffer)
      if (read <= 0) {
        break
      }
      digest.update(buffer, 0, read)
    }
  }
}
