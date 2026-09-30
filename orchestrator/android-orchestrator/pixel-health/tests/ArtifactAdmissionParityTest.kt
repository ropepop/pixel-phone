package lv.jolkins.pixelorchestrator.runtimeinstaller
import org.junit.Assert.*
import org.junit.Test

class ArtifactAdmissionParityTest {
  @Test fun manifestAdmissionOrderAndFailurePrecedenceMatchFormerOwner() {
    var comparisons=0
    fun compare(old:()->Any?,next:()->Any?) {
      val a=runCatching(old);val b=runCatching(next)
      assertEquals(a.getOrNull(),b.getOrNull())
      assertEquals(a.exceptionOrNull()?.javaClass,b.exceptionOrNull()?.javaClass)
      assertEquals(a.exceptionOrNull()?.message,b.exceptionOrNull()?.message);comparisons++
    }
    fun entry(id:String,url:String="/fixture",required:Boolean=true)=ArtifactEntry(id,url,"","fixture",required=required)
    val lists=listOf(emptyList(),listOf(entry("dropbear-bundle")),listOf(entry("tailscale-bundle")),
      listOf(entry("dropbear-bundle"),entry("tailscale-bundle")),listOf(entry("adguardhome-rootfs"),entry("dns-runtime-assets")),
      listOf(entry("dns-runtime-assets"),entry("adguardhome-rootfs")),listOf(entry("adguardhome-rootfs"),entry("adguardhome-rootfs")),
      listOf(entry("dropbear-bundle"),entry("dropbear-bundle",required=false),entry("tailscale-bundle")))
    val urls=listOf(""," ","\u2007","relative","http://example.invalid/fixture","HTTPS://example.invalid/fixture","/fixture","FILE:///fixture"," file:///fixture ","file://relative")
    for(signature in listOf("none","NONE"," none ","","signed")) for(version in listOf(""," ","\u2007","v1"))
      for(artifacts in lists) for(url in urls) for(required in listOf(false,true)) {
        val configured=artifacts.map {it.copy(url=url,required=required)}+entry("optional-fixture",url,required)
        val manifest=ArtifactManifest(manifestVersion=version,signatureSchema=signature,artifacts=configured)
        compare({LegacyArtifactAdmission.validateManifest(manifest)},{ArtifactAdmission.validateBootstrap(manifest,listOf("dropbear-bundle","tailscale-bundle"),listOf("optional-fixture"),"none")})
        for(rootfs in listOf(null,"adguardhome-rootfs","dropbear-bundle"))
          compare({LegacyArtifactAdmission.ensureRequiredArtifactsPresent(manifest,rootfs)},{ArtifactAdmission.validateRequired(manifest,rootfs)})
      }
    for(component in listOf("dns","ssh","vpn","unsupported")) for(target in listOf("dns","ssh",""))
      for(schema in listOf(-1,1,2)) for(signature in listOf("none","NONE",""," none ")) for(release in listOf("","\u2007","fixture"))
        for(artifacts in lists) for(url in urls) {
          val manifest=ComponentReleaseManifest(schema,target,release,signature,artifacts.map {it.copy(url=url)})
          compare({LegacyArtifactAdmission.validateComponentReleaseManifest(component,manifest)},{ArtifactAdmission.validateComponent(component,manifest,true)})
          compare({LegacyArtifactAdmission.ensureComponentReleasePresent(component,manifest)},{ArtifactAdmission.validateComponent(component,manifest,false)})
        }
    for(component in listOf("dns","ssh","")) for(artifacts in lists)
      compare({LegacyArtifactAdmission.orderedComponentReleaseArtifacts(component,artifacts)},{ArtifactAdmission.orderComponent(component,artifacts)})
    println("ARTIFACT_ADMISSION_PARITY_OK comparisons=$comparisons")
  }
}
