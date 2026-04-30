package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TicketScreenConfig {
  const val ACTION_START_SERVER = "lv.jolkins.pixelorchestrator.ticket.START_SERVER"
  const val ACTION_STOP_SERVER = "lv.jolkins.pixelorchestrator.ticket.STOP_SERVER"
  const val ACTION_MEDIA_PROJECTION_RESULT = "lv.jolkins.pixelorchestrator.ticket.MEDIA_PROJECTION_RESULT"
  const val EXTRA_RESULT_CODE = "ticket_media_projection_result_code"
  const val EXTRA_RESULT_DATA = "ticket_media_projection_result_data"

  const val SERVICE_PORT = 9388
  const val VIVI_PACKAGE = "com.pv.vivi"
  const val ACCRESCENT_PACKAGE = "app.accrescent.client"
  const val MAX_FPS = 10
  const val MAX_EQUIVALENT_PIXELS = 1920 * 1080
  const val AV1_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AV1
  const val AV1_CODEC_STRING = "av01.0.08M.08"

  val localStorePackages = listOf(
    ACCRESCENT_PACKAGE,
    "app.grapheneos.apps",
    "com.aurora.store",
    "org.fdroid.fdroid",
    "dev.imranr.obtainium"
  )
}

@Serializable
data class TicketStreamHealth(
  val ok: Boolean,
  val serverRunning: Boolean,
  val av1HardwareEncoderAvailable: Boolean,
  val projectionReady: Boolean,
  val viviInstalled: Boolean,
  val accrescentInstalled: Boolean,
  val installedStorePackages: List<String>,
  val streamActive: Boolean,
  val clients: Int,
  val inactivityActive: Boolean,
  val inactivityTimeoutMillis: Long,
  val inactivityRemainingMillis: Long,
  val autoStartAllowed: Boolean = true,
  val autoStartBlockedReason: String? = null,
  val message: String
)

@Serializable
data class TicketInactivityStatus(
  val type: String = "idle",
  val active: Boolean,
  val timeoutMillis: Long,
  val remainingMillis: Long
)

@Serializable
data class TicketSessionResponse(
  val ok: Boolean,
  val state: String,
  val message: String
)

data class TicketStreamSize(
  val width: Int,
  val height: Int,
  val sourceWidth: Int,
  val sourceHeight: Int
) {
  fun sourceX(encodedX: Int): Int {
    return ((encodedX.coerceIn(0, width) / width.toFloat()) * sourceWidth).roundToInt()
      .coerceIn(0, sourceWidth)
  }

  fun sourceY(encodedY: Int): Int {
    return ((encodedY.coerceIn(0, height) / height.toFloat()) * sourceHeight).roundToInt()
      .coerceIn(0, sourceHeight)
  }
}

object TicketStreamSizing {
  fun fitTo1080Equivalent(sourceWidth: Int, sourceHeight: Int): TicketStreamSize {
    val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
    if (sourcePixels <= TicketScreenConfig.MAX_EQUIVALENT_PIXELS) {
      return TicketStreamSize(
        width = sourceWidth.evenAtLeastTwo(),
        height = sourceHeight.evenAtLeastTwo(),
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
      )
    }
    val scale = sqrt(TicketScreenConfig.MAX_EQUIVALENT_PIXELS.toDouble() / sourcePixels.toDouble())
    return TicketStreamSize(
      width = (sourceWidth * scale).roundToInt().evenAtLeastTwo(),
      height = (sourceHeight * scale).roundToInt().evenAtLeastTwo(),
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight
    )
  }

  private fun Int.evenAtLeastTwo(): Int {
    val atLeastTwo = coerceAtLeast(2)
    return if (atLeastTwo % 2 == 0) atLeastTwo else atLeastTwo - 1
  }
}

internal object TicketInactivityPolicy {
  const val TIMEOUT_MILLIS = 10 * 60 * 1_000L
  const val TICK_MILLIS = 1_000L

  fun remainingMillis(
    lastInputAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Long {
    val elapsed = (nowMillis - lastInputAtMillis).coerceAtLeast(0L)
    return (timeoutMillis - elapsed).coerceIn(0L, timeoutMillis)
  }

  fun timedOut(
    lastInputAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = TIMEOUT_MILLIS
  ): Boolean {
    return remainingMillis(
      lastInputAtMillis = lastInputAtMillis,
      nowMillis = nowMillis,
      timeoutMillis = timeoutMillis
    ) <= 0L
  }
}

object TicketAv1Support {
  fun hardwareEncoderName(): String? {
    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    return codecs.firstOrNull { codecInfo ->
      codecInfo.isEncoder &&
        !codecInfo.isSoftwareOnlyCompat() &&
        codecInfo.supportedTypes.any { type -> type.equals(TicketScreenConfig.AV1_MIME_TYPE, ignoreCase = true) }
    }?.name
  }

  fun isHardwareEncoderAvailable(): Boolean = hardwareEncoderName() != null

  private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      isSoftwareOnly
    } else {
      val lower = name.lowercase()
      lower.startsWith("omx.google.") ||
        lower.startsWith("c2.android.") ||
        lower.contains("sw") ||
        lower.contains("software")
    }
  }
}

object TicketPackageSupport {
  fun isInstalled(context: Context, packageName: String): Boolean {
    return runCatching {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
  }

  fun installedLocalStores(context: Context): List<String> {
    return TicketScreenConfig.localStorePackages.filter { packageName ->
      isInstalled(context, packageName)
    }
  }
}
