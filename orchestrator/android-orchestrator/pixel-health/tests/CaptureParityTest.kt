package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class CaptureParityTest {
  @Test fun cadenceExpiryProofCoalescenceAndDeadlineErrorsMatchFormerOwner() {
    var comparisons = 0
    val times = listOf(Long.MIN_VALUE,-1L,0L,1L,999L,1000L,2500L,Long.MAX_VALUE-1000L,Long.MAX_VALUE)
    for (initial in times) {
      val old = LegacyCaptureCadence(initial); val next = TicketCaptureCadenceScheduler(initial)
      val random = Random(21)
      repeat(500) {
        val now = times[random.nextInt(times.size)]; val valid = times[random.nextInt(times.size)]
        val proof = random.nextBoolean()
        fun compare(a: () -> Any?, b: () -> Any?) {
          val expected = runCatching(a); val actual = runCatching(b)
          assertEquals(expected.exceptionOrNull()?.message,actual.exceptionOrNull()?.message)
          assertEquals(expected.getOrNull(),actual.getOrNull()); comparisons++
        }
        when (random.nextInt(6)) {
          0 -> compare({old.waitMillis(now,proof)},{next.waitMillis(now,proof)})
          1 -> compare({old.enableDemandGateAndLatchOrdinaryCapture(valid,now)},{next.enableDemandGateAndLatchOrdinaryCapture(valid,now)})
          2 -> compare({old.parkOrdinaryCapture()},{next.parkOrdinaryCapture()})
          3 -> compare({old.notePictureEmitted(now)},{next.notePictureEmitted(now)})
          4 -> compare({old.restartPeriodFrom(now)},{next.restartPeriodFrom(now)})
          5 -> compare({old.beginCapture(now,proof)},{next.beginCapture(now,proof)})
        }
        for (probe in listOf(now,now+1L)) for (bypass in listOf(false,true))
          compare({old.waitMillis(probe,bypass)},{next.waitMillis(probe,bypass)})
      }
    }
    println("CAPTURE_CADENCE_PARITY_OK comparisons=$comparisons")
  }
  @Test fun demandWireAdmissionAndCleanupMatchFormerOwner() {
    var comparisons = 0
    val json = Json
    val basic = """{"type":"capture_demand","version":1,"streamEpoch":7,"generation":2,"ttlMillis":2500}"""
    for (key in listOf("type","version","streamEpoch","generation","ttlMillis")) {
      val objectValue = json.parseToJsonElement(basic).jsonObject
      for (literal in listOf("null","true","false","0","-1","+1","01","1.0","1e0","9007199254740991","9007199254740992","9223372036854775808","\"2\"","[]","{}","\"capture_demand\"")) {
        val candidate = JsonObject(objectValue.toMutableMap().apply {put(key,json.parseToJsonElement(literal))})
        assertEquals("$key=$literal",LegacyCaptureDemandProtocol.parseRequest(candidate),TicketCaptureDemandProtocol.parseRequest(candidate)); comparisons++
      }
      val missing = JsonObject(objectValue.filterKeys {it != key})
      assertEquals(LegacyCaptureDemandProtocol.parseRequest(missing),TicketCaptureDemandProtocol.parseRequest(missing)); comparisons++
    }
    val extra = JsonObject(json.parseToJsonElement(basic).jsonObject+mapOf("extra" to JsonPrimitive(true)))
    assertEquals(LegacyCaptureDemandProtocol.parseRequest(extra),TicketCaptureDemandProtocol.parseRequest(extra)); comparisons++
    assertEquals(LegacyCaptureDemandProtocol.parseRequest(json.parseToJsonElement(basic).jsonObject),TicketCaptureDemandProtocol.parseRequest(json.parseToJsonElement(basic).jsonObject)); comparisons++
    val old = LegacyCaptureDemandSession(); val next = TicketCaptureDemandSession()
    for (epoch in listOf(-1L,0L,7L,8L)) for (generation in listOf(0L,1L,2L,3L,Long.MAX_VALUE))
      for (received in listOf(Long.MIN_VALUE,0L,1L,1000L,Long.MAX_VALUE-1000L,Long.MAX_VALUE))
        for (now in listOf(-1L,1000L,3500L,3501L,Long.MAX_VALUE)) for (available in listOf(false,true)) {
          val deliveredOld = mutableListOf<Long>(); val deliveredNext = mutableListOf<Long>()
          val request = TicketCaptureDemandRequest(7L,generation,2500L)
          assertEquals(old.admit(request,epoch,received,now){deliveredOld+=it;available},next.admit(request,epoch,received,now){deliveredNext+=it;available})
          assertEquals(deliveredOld,deliveredNext); assertEquals(old.lastAcceptedGeneration(),next.lastAcceptedGeneration()); comparisons++
        }
    for (expected in listOf(null,-1L,0L,1L,2L)) for (current in listOf(-1L,0L,1L,2L)) for (active in listOf(false,true))
      for (clients in listOf(-1,0,1)) for (bits in 0..15) {
        val s = bits and 1 != 0; val c = bits and 2 != 0; val a = bits and 4 != 0; val r = bits and 8 != 0
        assertEquals(legacyCaptureCleanup(expected,current,active,clients,s,c,a,r),ticketProofStreamCleanupDecision(expected,current,active,clients,s,c,a,r)); comparisons++
      }
    println("CAPTURE_DEMAND_CLEANUP_PARITY_OK comparisons=$comparisons")
  }
}
