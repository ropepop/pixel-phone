package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.InputStream

interface AssetProvider {
  fun open(path: String): InputStream
  fun list(path: String): List<String>
}
