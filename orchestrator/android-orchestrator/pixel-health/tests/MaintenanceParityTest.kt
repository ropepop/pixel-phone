package lv.jolkins.pixelorchestrator.app

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random
import lv.jolkins.pixelorchestrator.supervisor.BackoffPolicy
import lv.jolkins.pixelorchestrator.supervisor.LegacyBackoffPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceParityTest {
  @Test fun restartHistoryResetClockChangesAndOverflowMatchFrozenOwner() {
    val parameters = listOf(
      listOf(5, 20, 300, 3), listOf(1, 64, 60, 2), listOf(0, 0, 0, 0),
      listOf(-1, -2, -1, -1), listOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
      listOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
    )
    val times = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 60L, 61L, 300L, 301L, Long.MAX_VALUE)
    var comparisons = 0
    for (p in parameters) for (initial in times) {
      var now = initial
      val old = LegacyBackoffPolicy(p[0],p[1],p[2],p[3]) { now }
      val native = BackoffPolicy(p[0],p[1],p[2],p[3]) { now }
      val random = Random(123)
      repeat(400) { index ->
        now = if (index % 13 == 0) times[random.nextInt(times.size)] else now + listOf(-1L,0L,1L,p[2].toLong(),p[2].toLong()+1L)[random.nextInt(5)]
        if (index % 47 == 0) { old.reset(); native.reset() }
        assertEquals(old.recordRestart(), native.recordRestart()); comparisons++
      }
    }
    println("MAINTENANCE_BACKOFF_PARITY_OK comparisons=$comparisons")
  }

  @Test fun calendarBoundariesGapsOverlapsSkippedDatesAndExactAlarmMatchFrozenOwner() {
    val zones = listOf("Europe/Riga","UTC","America/New_York","Australia/Lord_Howe","Pacific/Apia")
    val dates = listOf("2026-03-23T02:59:59", "2026-03-23T03:00:00", "2026-03-29T01:00:00", "2026-10-25T03:00:00", "2011-12-29T23:59:59", "2011-12-31T03:00:00", "2000-02-29T03:00:00", "1900-03-01T03:00:00")
    var comparisons = 0
    for (zone in zones) for (date in dates) for (nano in listOf(0,1,999999999)) {
      val now = java.time.LocalDateTime.parse(date).withNano(nano).atZone(ZoneId.of(zone))
      for (day in DayOfWeek.values()) for (hour in listOf(0,2,3,23)) for (minute in listOf(0,59)) {
        assertEquals(LegacyWeeklyCleanupSchedulePolicy.nextRunAfter(now,day,hour,minute), WeeklyCleanupSchedulePolicy.nextRunAfter(now,day,hour,minute)); comparisons++
        assertEquals(LegacyWeeklyCleanupSchedulePolicy.nextRunAfter(now.toInstant().toEpochMilli(),now.zone,day,hour,minute), WeeklyCleanupSchedulePolicy.nextRunAfter(now.toInstant().toEpochMilli(),now.zone,day,hour,minute)); comparisons++
      }
    }
    // ZonedDateTime's public range exceeds chrono's calendar range. Android's
    // existing calendar adapter must preserve it rather than introduce a limit.
    for (year in listOf(-999999999,-262144,262144,999999999)) {
      val now = ZonedDateTime.of(year,6,15,2,59,59,999999999,ZoneId.of("UTC"))
      assertEquals(LegacyWeeklyCleanupSchedulePolicy.nextRunAfter(now),WeeklyCleanupSchedulePolicy.nextRunAfter(now)); comparisons++
    }
    for (sdk in listOf(Int.MIN_VALUE,0,22,30,31,32,36,Int.MAX_VALUE)) for (granted in listOf(false,true)) {
      assertEquals(LegacyWeeklyCleanupAlarmPolicy.mode(sdk,granted),WeeklyCleanupAlarmPolicy.mode(sdk,granted)); comparisons++
    }
    println("MAINTENANCE_CALENDAR_ALARM_PARITY_OK comparisons=$comparisons")
  }
}
