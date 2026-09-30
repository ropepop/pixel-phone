package lv.jolkins.pixelorchestrator.runtimeinstaller
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ArtifactParityTest {
 @Test fun checksumCacheReplacementSourceRejectionAndPrivateReleaseMatchFormerOwner() {
  val root=Files.createTempDirectory("native-artifact-parity");var comparisons=0
  try {
   val oldCache=Files.createDirectory(root.resolve("old"));val nextCache=Files.createDirectory(root.resolve("next"))
   val old=LegacyArtifactSyncer(oldCache);val next=ArtifactSyncer(nextCache)
   val source=root.resolve("source.bin")
   for(size in listOf(0,1,8191,8192,8193,65536,1024*1024)) {
    Files.write(source,ByteArray(size){(it*31).toByte()})
    assertEquals(old.sha256(source),next.sha256(source));comparisons++
   }
   val urls=listOf(""," ","\u2007","relative", "http://example.invalid/a", "HTTPS://example.invalid/a", "/missing-artifact-test-file",
    source.toString(), " file://$source ","FILE://$source", "$source\u2007")
   for((ordinal,url) in urls.withIndex()) for(sha in listOf("", "wrong", old.sha256(source), " \u2007"+old.sha256(source).uppercase()+"\u2007 ")) {
    val entry=ArtifactEntry("fixture",url,sha,"artifact-$ordinal.bin",0,true)
    val a=runCatching {old.sync(entry)};val b=runCatching {next.sync(entry)}
    assertEquals(a.isSuccess,b.isSuccess)
    assertEquals(a.exceptionOrNull()?.javaClass,b.exceptionOrNull()?.javaClass)
    assertEquals(a.exceptionOrNull()?.message?.replace(oldCache.toString(),"CACHE"),b.exceptionOrNull()?.message?.replace(nextCache.toString(),"CACHE"))
    val af=oldCache.resolve(entry.fileName);val bf=nextCache.resolve(entry.fileName)
    assertEquals(Files.exists(af),Files.exists(bf))
    if(Files.exists(af)) {
      assertArrayEquals(Files.readAllBytes(af),Files.readAllBytes(bf))
      assertEquals(Files.getPosixFilePermissions(af),Files.getPosixFilePermissions(bf))
    };comparisons++
   }
   for(relative in listOf("missing", "artifact-7.bin", "sub/file", "../source.bin", "sub/../artifact-8.bin")) {
    val af=oldCache.resolve(relative);val bf=nextCache.resolve(relative)
    assertEquals(old.release(af),next.release(bf));assertEquals(Files.exists(af),Files.exists(bf));comparisons++
   }
   val targetOld=oldCache.resolve("replace.bin");val targetNext=nextCache.resolve("replace.bin")
   Files.writeString(targetOld,"stale");Files.writeString(targetNext,"stale")
   val entry=ArtifactEntry("fixture",source.toString(),old.sha256(source),"replace.bin",0,true)
   assertArrayEquals(Files.readAllBytes(old.sync(entry)),Files.readAllBytes(next.sync(entry)));comparisons++
   println("ARTIFACT_PARITY_OK comparisons=$comparisons")
  } finally {Files.walk(root).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}}
 }
}
