package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.math.roundToInt

internal fun TicketStreamSize.sourceX(encodedX: Int): Int =
  (sourceLeftCrop +
    ((encodedX.coerceIn(0, width) / width.toFloat()) * sourceVisibleWidth).roundToInt())
    .coerceIn(sourceLeftCrop, sourceWidth - sourceRightCrop)

internal fun TicketStreamSize.sourceY(encodedY: Int): Int =
  (sourceTopCrop + ((encodedY.coerceIn(0, height) / height.toFloat()) * sourceVisibleHeight).roundToInt())
    .coerceIn(sourceTopCrop, sourceHeight - sourceBottomCrop)
