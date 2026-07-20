package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ArtifactSyncerDownloadTest {

  @Test
  fun syncCopiesArtifactFromAbsoluteLocalPath() {
    val sourceDir = Files.createTempDirectory("artifact-syncer-source")
    val cacheDir = Files.createTempDirectory("artifact-syncer-cache")
    val source = sourceDir.resolve("artifact.tar")
    Files.writeString(source, "artifact-bytes")

    val syncer = ArtifactSyncer(cacheDir)
    val entry = ArtifactEntry(
      id = "artifact",
      url = source.toString(),
      sha256 = "",
      fileName = "artifact.tar",
      sizeBytes = 0,
      required = true
    )

    val target = syncer.sync(entry)
    assertTrue(Files.exists(target))
    assertEquals("artifact-bytes", Files.readString(target))
  }

  @Test
  fun syncRejectsRemoteHttpSource() {
    val cacheDir = Files.createTempDirectory("artifact-syncer-http")
    val syncer = ArtifactSyncer(cacheDir)

    val entry = ArtifactEntry(
      id = "artifact",
      url = "https://example.invalid/artifact.tar",
      sha256 = "",
      fileName = "artifact.tar",
      sizeBytes = 0,
      required = true
    )

    val error = assertFailsWith<IllegalStateException> {
      syncer.sync(entry)
    }

    assertTrue(error.message.orEmpty().contains("Remote artifact source is not allowed"))
  }

  @Test
  fun syncFailsWhenLocalSourceMissing() {
    val cacheDir = Files.createTempDirectory("artifact-syncer-missing")
    val syncer = ArtifactSyncer(cacheDir)

    val entry = ArtifactEntry(
      id = "artifact",
      url = "/tmp/does-not-exist-artifact.tar",
      sha256 = "",
      fileName = "artifact.tar",
      sizeBytes = 0,
      required = true
    )

    val error = assertFailsWith<IllegalStateException> {
      syncer.sync(entry)
    }

    assertTrue(error.message.orEmpty().contains("Artifact sync failed"))
  }

  @Test
  fun syncReplacesStaleCachedArtifactWhenShaChangesUnderSameFileName() {
    val sourceDir = Files.createTempDirectory("artifact-syncer-refresh-source")
    val cacheDir = Files.createTempDirectory("artifact-syncer-refresh-cache")
    val source = sourceDir.resolve("artifact.tar")
    Files.writeString(source, "fresh-artifact")

    val target = cacheDir.resolve("artifact.tar")
    Files.writeString(target, "stale-artifact")

    val syncer = ArtifactSyncer(cacheDir)
    val entry = ArtifactEntry(
      id = "artifact",
      url = source.toString(),
      sha256 = sha256("fresh-artifact".toByteArray()),
      fileName = "artifact.tar",
      sizeBytes = 0,
      required = true
    )

    val synced = syncer.sync(entry)

    assertEquals(target, synced)
    assertEquals("fresh-artifact", Files.readString(synced))
  }

  @Test
  fun releaseDeletesOnlyThePrivateStagingCopy() {
    val sourceDir = Files.createTempDirectory("artifact-syncer-release-source")
    val cacheDir = Files.createTempDirectory("artifact-syncer-release-cache")
    val source = sourceDir.resolve("artifact.tar")
    Files.writeString(source, "artifact-bytes")
    val syncer = ArtifactSyncer(cacheDir)
    val entry = ArtifactEntry(
      id = "artifact",
      url = source.toString(),
      sha256 = sha256("artifact-bytes".toByteArray()),
      fileName = "artifact.tar",
      sizeBytes = Files.size(source),
      required = true
    )

    val staged = syncer.sync(entry)
    syncer.release(staged)
    syncer.release(source)

    assertFalse(Files.exists(staged))
    assertTrue(Files.exists(source))
  }

  private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
  }
}
