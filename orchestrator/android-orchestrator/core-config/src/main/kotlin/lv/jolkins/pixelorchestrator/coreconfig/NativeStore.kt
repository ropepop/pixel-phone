package lv.jolkins.pixelorchestrator.coreconfig

internal object NativeStore {
  init { System.loadLibrary("pixel_health") }

  @JvmStatic external fun readUtf8OrNull(path: String): String?
  @JvmStatic external fun writeAtomic(path: String, body: String)
  @JvmStatic external fun legacyRemoteAliases(remoteStrings: String): String
  @JvmStatic external fun redactToken(value: String): String
}
