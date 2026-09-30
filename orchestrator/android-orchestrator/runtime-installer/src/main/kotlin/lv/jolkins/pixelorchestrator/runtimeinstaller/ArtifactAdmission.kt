package lv.jolkins.pixelorchestrator.runtimeinstaller

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Manifest decisions share the artifact owner; callers retain platform effects and exception types. */
object ArtifactAdmission {
  private val json = Json
  fun validateBootstrap(manifest: ArtifactManifest, required: List<String>, optional: List<String>, signature: String) {
    validateAccessArtifacts(manifest.artifacts)
    val args = buildJsonObject {
      put("manifest", json.encodeToJsonElement(manifest))
      put("required", JsonArray(required.map(::JsonPrimitive)))
      put("optional", JsonArray(optional.map(::JsonPrimitive)))
      put("signature", signature)
    }
    checkError("bootstrap_manifest", args)
  }
  fun validateRequired(manifest: ArtifactManifest, rootfs: String?) {
    check(rootfs == null) { "Phone rootfs installation is retired; use the VPS" }
    validateAccessArtifacts(manifest.artifacts)
    checkError("required_manifest", buildJsonObject {put("manifest",json.encodeToJsonElement(manifest));put("rootfs",rootfs)})
  }
  fun validateComponent(component: String, manifest: ComponentReleaseManifest, facade: Boolean) {
    val expectedArtifact = when (component) {
      "ssh" -> "dropbear-bundle"
      "vpn" -> "tailscale-bundle"
      else -> error("Phone component installation supports only SSH/Tailscale access; other workloads belong on the VPS")
    }
    val accessOnly = manifest.artifacts.all { it.id == expectedArtifact }
    val error = "Phone component release does not match its SSH/Tailscale access owner"
    if (facade) check(accessOnly) { error } else require(accessOnly) { error }
    val failure = NativeArtifact.call("component_manifest", buildJsonObject {
      put("component",component);put("manifest",json.encodeToJsonElement(manifest));put("facade",facade)
    }).jsonPrimitive.contentOrNull
    if (facade) check(failure == null) {failure.orEmpty()} else require(failure == null) {failure.orEmpty()}
  }
  fun orderComponent(component: String, artifacts: List<ArtifactEntry>): List<ArtifactEntry> {
    val result = NativeArtifact.call("component_order",buildJsonObject {
      put("component",component);put("artifacts",json.encodeToJsonElement(artifacts))
    }).jsonObject
    val failure = result.getValue("error").jsonPrimitive.contentOrNull
    check(failure == null) {failure.orEmpty()}
    return result.getValue("indices").jsonArray.map {artifacts[it.jsonPrimitive.int]}
  }
  private fun checkError(operation: String, args: JsonObject) {
    val failure = NativeArtifact.call(operation,args).jsonPrimitive.contentOrNull
    check(failure == null) {failure.orEmpty()}
  }
  private fun validateAccessArtifacts(artifacts: List<ArtifactEntry>) {
    check(artifacts.all { it.id == "dropbear-bundle" || it.id == "tailscale-bundle" }) {
      "Phone deployment supports only Dropbear/Tailscale access artifacts; other workloads belong on the VPS"
    }
  }
}
