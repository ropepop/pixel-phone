package lv.jolkins.pixelorchestrator.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/** Grants a receiver one read and removes the private archive when that read closes. */
class ExpiringSupportFileProvider : FileProvider() {
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw FileNotFoundException("Support archives are read-only")
    val archiveName = uri.lastPathSegment.orEmpty()
    if (!archiveName.matches(ARCHIVE_NAME)) throw FileNotFoundException("Unknown support archive")
    val supportDir = File(requireNotNull(context).cacheDir, SUPPORT_CACHE_DIR).canonicalFile
    val archive = File(supportDir, archiveName).canonicalFile
    if (archive.parentFile != supportDir || !archive.isFile) {
      throw FileNotFoundException("Support archive unavailable")
    }
    return ParcelFileDescriptor.open(
      archive,
      ParcelFileDescriptor.MODE_READ_ONLY,
      Handler(Looper.getMainLooper())
    ) {
      archive.delete()
    }
  }

  private companion object {
    const val SUPPORT_CACHE_DIR = "support-bundles"
    val ARCHIVE_NAME = Regex("pixel-stack-support-[0-9]+\\.zip")
  }
}
