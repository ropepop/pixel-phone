package lv.jolkins.pixelorchestrator.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal object WeeklyCleanupSchedulePolicy {
  val TARGET_DAY_OF_WEEK: DayOfWeek = DayOfWeek.MONDAY
  const val TARGET_HOUR = 3
  const val TARGET_MINUTE = 0

  fun nextRunAfter(
    now: ZonedDateTime,
    dayOfWeek: DayOfWeek = TARGET_DAY_OF_WEEK,
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): ZonedDateTime {
    val candidate = now
      .toLocalDate()
      .plusDays(NativeCleanupSchedule.daysAhead(now.dayOfWeek.value, dayOfWeek.value))
      .atTime(hour, minute)
      .atZone(now.zone)
    return if (NativeCleanupSchedule.nextWeek(now.toEpochSecond(), now.nano, candidate.toEpochSecond(), candidate.nano)) {
      candidate.toLocalDate().plusWeeks(1).atTime(hour, minute).atZone(now.zone)
    } else candidate
  }

  fun nextRunAfter(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    dayOfWeek: DayOfWeek = TARGET_DAY_OF_WEEK,
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): Long {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
    return nextRunAfter(now, dayOfWeek, hour, minute).toInstant().toEpochMilli()
  }
}

/** Java resolves the device time-zone rules; Rust decides dates and admission. */
internal object NativeCleanupSchedule {
  init { System.loadLibrary("pixel_health") }
  @JvmStatic external fun daysAhead(currentDay: Int, targetDay: Int): Long
  @JvmStatic external fun nextWeek(nowSeconds: Long, nowNanos: Int, candidateSeconds: Long, candidateNanos: Int): Boolean
  @JvmStatic external fun exactAlarm(sdk: Int, granted: Boolean): Boolean
}
