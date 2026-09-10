package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketEncoderTeardownTest {
  @Test fun failedSurfaceReleaseCannotSkipCodecOrPictureRelease() {
    val order = mutableListOf<String>()
    assertThrows(TicketEncoderTeardown.UnsafeReleaseException::class.java) {
      TicketEncoderTeardown.release(
        { order.add("surface"); throw IllegalStateException() },
        { order.add("stop"); throw IllegalStateException() },
        { order.add("codec") }, { order.add("source") }
      )
    }
    assertEquals(listOf("surface", "stop", "codec", "source"), order)
  }

  @Test fun unknownCodecOwnershipPinsPictureAndForbidsInProcessRecovery() {
    val order = mutableListOf<String>()
    assertThrows(TicketEncoderTeardown.UnsafeReleaseException::class.java) {
      TicketEncoderTeardown.release(
        { order.add("surface") }, { order.add("stop") },
        { order.add("codec"); throw IllegalStateException() }, { order.add("source") }
      )
    }
    assertEquals(listOf("surface", "stop", "codec"), order)
  }

  @Test fun stopFailureIsRecoverableWhenNativeReleaseSucceeds() {
    var sourceReleased = false
    TicketEncoderTeardown.release({}, { throw IllegalStateException() }, {}, { sourceReleased = true })
    assertTrue(sourceReleased)
  }

  @Test fun pictureReleaseFailureAlsoRequiresProcessCleanup() {
    assertThrows(TicketEncoderTeardown.UnsafeReleaseException::class.java) {
      TicketEncoderTeardown.release({}, {}, {}, { throw IllegalStateException() })
    }
  }
}
