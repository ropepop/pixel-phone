package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class TicketCapturePermissionActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val manager = getSystemService(MediaProjectionManager::class.java)
    val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
      manager.createScreenCaptureIntent()
    }
    startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_MEDIA_PROJECTION) {
      finish()
      return
    }
    val serviceIntent = Intent(this, TicketStreamService::class.java)
      .setAction(TicketScreenConfig.ACTION_MEDIA_PROJECTION_RESULT)
      .putExtra(TicketScreenConfig.EXTRA_RESULT_CODE, resultCode)
    if (resultCode == Activity.RESULT_OK && data != null) {
      serviceIntent.putExtra(TicketScreenConfig.EXTRA_RESULT_DATA, data)
    }
    ContextCompat.startForegroundService(this, serviceIntent)
    finish()
  }

  companion object {
    private const val REQUEST_MEDIA_PROJECTION = 9389
  }
}
