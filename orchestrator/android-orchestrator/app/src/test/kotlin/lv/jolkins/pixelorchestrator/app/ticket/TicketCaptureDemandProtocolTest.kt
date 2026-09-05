package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketCaptureDemandProtocolTest {
  @Test
  fun parsesOnlyTheExactVersionOneDemandEnvelope() {
    val request = parse(
      """{"type":"capture_demand","version":1,"streamEpoch":37,"generation":9,"ttlMillis":2500}"""
    )!!

    assertEquals(37L, request.streamEpoch)
    assertEquals(9L, request.generation)
    assertEquals(2_500L, request.ttlMillis)
  }

  @Test
  fun rejectsUnknownMissingStringFractionalUnsafeAndOutOfRangeFields() {
    val unsafe = TicketTsf3FrameEnvelope.MAX_SAFE_INTEGER + 1L
    val invalid = listOf(
      """{"type":"capture_demand","version":1,"streamEpoch":37,"generation":9,"ttlMillis":2500,"extra":true}""",
      """{"type":"capture_demand","version":1,"streamEpoch":37,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":"1","streamEpoch":37,"generation":9,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":"37","generation":9,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":37,"generation":9.0,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":37,"generation":9,"ttlMillis":2499}""",
      """{"type":"capture_demand","version":2,"streamEpoch":37,"generation":9,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":0,"generation":9,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":37,"generation":0,"ttlMillis":2500}""",
      """{"type":"capture_demand","version":1,"streamEpoch":$unsafe,"generation":9,"ttlMillis":2500}""",
      """{"type":"other","version":1,"streamEpoch":37,"generation":9,"ttlMillis":2500}"""
    )

    invalid.forEach { raw -> assertNull(raw, parse(raw)) }
  }

  @Test
  fun oneSocketAcceptsOnlyIncreasingCurrentEpochDemandBeforeItsDeadline() {
    val session = TicketCaptureDemandSession()
    val deliveries = mutableListOf<Long>()
    fun admit(generation: Long, epoch: Long = 37L, now: Long = 10_000L) = session.admit(
      request = TicketCaptureDemandRequest(epoch, generation, 2_500L),
      currentStreamEpoch = 37L,
      receivedAtUptimeMillis = 10_000L,
      nowUptimeMillis = now,
      deliverToHelper = { deadline -> deliveries += deadline; true }
    )

    assertEquals(TicketCaptureDemandAdmissionResult.ACCEPTED, admit(1L))
    assertEquals(TicketCaptureDemandAdmissionResult.NON_MONOTONIC_GENERATION, admit(1L))
    assertEquals(TicketCaptureDemandAdmissionResult.NON_MONOTONIC_GENERATION, admit(0L))
    assertEquals(TicketCaptureDemandAdmissionResult.WRONG_EPOCH, admit(2L, epoch = 38L))
    assertEquals(TicketCaptureDemandAdmissionResult.EXPIRED, admit(2L, now = 12_501L))
    assertEquals(TicketCaptureDemandAdmissionResult.ACCEPTED, admit(2L, now = 12_500L))
    assertEquals(listOf(12_500L, 12_500L), deliveries)
  }

  @Test
  fun failedHelperDeliveryDoesNotConsumeTheConnectionGeneration() {
    val session = TicketCaptureDemandSession()
    val request = TicketCaptureDemandRequest(5L, 4L, 2_500L)

    assertEquals(
      TicketCaptureDemandAdmissionResult.HELPER_UNAVAILABLE,
      session.admit(request, 5L, 1_000L, 1_000L) { false }
    )
    assertEquals(0L, session.lastAcceptedGeneration())
    assertEquals(
      TicketCaptureDemandAdmissionResult.ACCEPTED,
      session.admit(request, 5L, 1_000L, 1_001L) { true }
    )
  }

  @Test
  fun aNewSocketSessionStartsWithAFreshGenerationScope() {
    val request = TicketCaptureDemandRequest(5L, 1L, 2_500L)
    val first = TicketCaptureDemandSession()
    val replacement = TicketCaptureDemandSession()

    assertEquals(
      TicketCaptureDemandAdmissionResult.ACCEPTED,
      first.admit(request, 5L, 1_000L, 1_000L) { true }
    )
    assertEquals(
      TicketCaptureDemandAdmissionResult.ACCEPTED,
      replacement.admit(request, 5L, 1_000L, 1_000L) { true }
    )
  }

  private fun parse(raw: String): TicketCaptureDemandRequest? =
    TicketCaptureDemandProtocol.parseRequest(Json.parseToJsonElement(raw).jsonObject)
}
