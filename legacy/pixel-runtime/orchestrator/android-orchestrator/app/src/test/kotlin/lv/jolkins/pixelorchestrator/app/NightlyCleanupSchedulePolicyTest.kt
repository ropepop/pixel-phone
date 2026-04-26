package lv.jolkins.pixelorchestrator.app

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyCleanupSchedulePolicyTest {

  @Test
  fun schedulesSameDayWhenBeforeThreeAm() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 25, 1, 15, 0, 0, zone)

    val next = NightlyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(2026, next.year)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertEquals(25, next.dayOfMonth)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun schedulesNextDayWhenPastThreeAm() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 25, 4, 0, 0, 0, zone)

    val next = NightlyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(26, next.dayOfMonth)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun springForwardGapMovesToFirstValidInstant() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)

    val next = NightlyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(29, next.dayOfMonth)
    assertEquals(4, next.hour)
    assertEquals(0, next.minute)
    assertTrue(next.isAfter(now))
  }

  @Test
  fun fallBackOverlapUsesFirstOccurrence() {
    val zone = ZoneId.of("Europe/Riga")
    val now = ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, zone)

    val next = NightlyCleanupSchedulePolicy.nextRunAfter(now)

    assertEquals(25, next.dayOfMonth)
    assertEquals(3, next.hour)
    assertEquals(0, next.minute)
    assertEquals(3 * 60 * 60, next.offset.totalSeconds)
    assertTrue(next.isAfter(now))
  }
}
