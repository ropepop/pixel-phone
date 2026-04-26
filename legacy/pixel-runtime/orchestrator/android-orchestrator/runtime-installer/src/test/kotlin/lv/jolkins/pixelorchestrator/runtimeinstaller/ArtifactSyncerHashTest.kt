package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.nio.file.Files
import kotlin.test.assertEquals
import org.junit.Test

class ArtifactSyncerHashTest {

  @Test
  fun computesSha256() {
    val dir = Files.createTempDirectory("artifact-syncer-test")
    val target = dir.resolve("a.txt")
    Files.writeString(target, "hello")

    val syncer = ArtifactSyncer(dir)
    val hash = syncer.sha256(target)

    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
  }
}
