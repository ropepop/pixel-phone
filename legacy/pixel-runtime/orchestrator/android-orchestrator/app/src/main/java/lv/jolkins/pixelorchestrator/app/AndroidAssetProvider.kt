package lv.jolkins.pixelorchestrator.app

import android.content.res.AssetManager
import java.io.InputStream
import lv.jolkins.pixelorchestrator.runtimeinstaller.AssetProvider

class AndroidAssetProvider(
  private val assetManager: AssetManager
) : AssetProvider {
  override fun open(path: String): InputStream {
    return assetManager.open(path)
  }

  override fun list(path: String): List<String> {
    return assetManager.list(path)?.toList().orEmpty()
  }
}
