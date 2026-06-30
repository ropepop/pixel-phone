package lv.jolkins.pixelorchestrator.app

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyCleanupSchedulePolicyTest {

  @Test
  fun schedulesSameMondayWhenBeforeThreeAm() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 23, 1, 15, 0, 0, zone)

    val next = WeeklyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(2026, next.year)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertEquals(23, next.dayOfMonth)
    assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun schedulesNextMondayWhenPastThreeAm() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 23, 4, 0, 0, 0, zone)

    val next = WeeklyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(30, next.dayOfMonth)
    assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun schedulesUpcomingMondayFromMiddleOfWeek() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 25, 4, 0, 0, 0, zone)

    val next = WeeklyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(30, next.dayOfMonth)
    assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun springForwardGapMovesToFirstValidInstant() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)

    val next = WeeklyCleanupSchedulePolicy.nextRunAfter(now, dayOfWeek = DayOfWeek.SUNDAY)

    assertEquals(29, next.dayOfMonth)
    assertEquals(4, next.hour)
    assertEquals(0, next.minute)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun fallBackOverlapUsesFirstOccurrence() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, zone)

    val next = WeeklyCleanupSchedulePolicy.nextRunAfter(now, dayOfWeek = DayOfWeek.SUNDAY)

    assertEquals(25, next.dayOfMonth)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertEquals(3 * 60 * 60, next.offset.totalSeconds)
    assertTrue(next.isAfter(now))
  }
}
