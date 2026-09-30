package lv.jolkins.pixelorchestrator.app.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.os.SystemClock
import java.nio.file.Files
import java.security.MessageDigest
import lv.jolkins.pixelorchestrator.runtimeinstaller.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real app-private files and installed JNI, without touching runtime releases or configuration. */
@RunWith(AndroidJUnit4::class)
class ArtifactNativeInstrumentedTest {
  @Test fun installedNativeFileHashHasRepresentativeWarmDeviceComparison() {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val root=Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(),"native-artifact-timing-")
    try {
      val source=root.resolve("source.bin");val sync=ArtifactSyncer(root.resolve("cache"));val timings=mutableListOf<String>()
      fun formerJavaDigest():String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(source).use {input->val buffer=ByteArray(8192);var n=input.read(buffer);while(n>=0){digest.update(buffer,0,n);n=input.read(buffer)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
      }
      for(size in listOf(65536,1024*1024,8*1024*1024)) {
        Files.write(source,ByteArray(size){(it*31).toByte()})
        val expected=formerJavaDigest();assertEquals(expected,sync.sha256(source))
        var nativeNanos=0L;var javaNanos=0L
        repeat(6) {index->
          fun javaRun(){val before=SystemClock.elapsedRealtimeNanos();val actual=formerJavaDigest();javaNanos+=SystemClock.elapsedRealtimeNanos()-before;assertEquals(expected,actual)}
          fun nativeRun(){val before=SystemClock.elapsedRealtimeNanos();val actual=sync.sha256(source);nativeNanos+=SystemClock.elapsedRealtimeNanos()-before;assertEquals(expected,actual)}
          if(index%2==0){javaRun();nativeRun()}else{nativeRun();javaRun()}
        }
        timings+="bytes=$size repetitions=6 native_nanos=$nativeNanos java_nanos=$javaNanos"
      }
      instrumentation.sendStatus(0,Bundle().apply {putString("artifact_warm_sha_timing",timings.joinToString("; "))})
    }finally{Files.walk(root).use {files->files.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}}
  }
  @Test fun nativeChecksumCacheReplacementAndManifestAdmissionUseInstalledLibrary() {
    val root=Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),"native-artifact-")
    try {
      val source=root.resolve("source.bin");val bytes=ByteArray(8193){(it*31).toByte()};Files.write(source,bytes)
      val expected=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
      val sync=ArtifactSyncer(root.resolve("cache"))
      assertEquals(expected,sync.sha256(source))
      val entry=ArtifactEntry("fixture",source.toString(),expected,"fixture.bin")
      val target=sync.sync(entry);assertArrayEquals(bytes,Files.readAllBytes(target))
      Files.write(target,byteArrayOf(1));assertArrayEquals(bytes,Files.readAllBytes(sync.sync(entry)))
      assertFalse(sync.release(source));assertTrue(Files.exists(source));assertTrue(sync.release(target))
      assertTrue(runCatching {sync.sync(entry.copy(url="https://example.invalid/fixture"))}.exceptionOrNull() is IllegalStateException)
      val manifest=ArtifactManifest(manifestVersion="fixture",signatureSchema="NONE",artifacts=listOf(entry.copy(id="dropbear-bundle"),entry.copy(id="tailscale-bundle")))
      ArtifactAdmission.validateBootstrap(manifest,listOf("dropbear-bundle","tailscale-bundle"),emptyList(),"none")
      ArtifactAdmission.validateRequired(manifest,null)
      val release=ComponentReleaseManifest(componentId="ssh",releaseId="fixture",signatureSchema="none",artifacts=listOf(entry.copy(id="dropbear-bundle")))
      ArtifactAdmission.validateComponent("ssh",release,true);ArtifactAdmission.validateComponent("ssh",release,false)
      assertEquals(release.artifacts,ArtifactAdmission.orderComponent("ssh",release.artifacts))
      assertTrue(runCatching {ArtifactAdmission.validateComponent("vpn",release,false)}.exceptionOrNull() is IllegalArgumentException)
    } finally {Files.walk(root).use {files->files.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}}
  }
}
