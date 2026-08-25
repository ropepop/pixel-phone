package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoClientDeliveryRegistryTest {
  @Test
  fun obsoleteTimeoutCannotApplyCloseOrKeyFrameEffectsToReplacementState() {
    val registry = TicketVideoClientDeliveryRegistry<String>()
    val old = state(epoch = 7L)
    val replacement = state(epoch = 8L)
    registry.add("relay", old)
    registry.replace("relay", replacement)
    var staleEffectApplied = false
    var replacementEffectApplied = false

    assertFalse(registry.ifCurrent("relay", old) { staleEffectApplied = true })
    assertTrue(registry.ifCurrent("relay", replacement) { replacementEffectApplied = true })

    assertFalse(staleEffectApplied)
    assertTrue(replacementEffectApplied)
    assertTrue(old.snapshot().closed)
    assertFalse(replacement.snapshot().closed)
  }

  @Test
  fun epochResetInvalidatesEveryQueuedDeliveryGeneration() {
    val registry = TicketVideoClientDeliveryRegistry<String>()
    val first = state(epoch = 7L)
    val second = state(epoch = 7L)
    registry.add("first", first)
    registry.add("second", second)

    registry.replaceAll { state(epoch = 8L) }

    assertTrue(first.snapshot().closed)
    assertTrue(second.snapshot().closed)
    assertTrue(registry.current("first")?.snapshot()?.expectedEpoch == 8L)
    assertTrue(registry.current("second")?.snapshot()?.expectedEpoch == 8L)
  }

  @Test
  fun staleConfigSnapshotCannotReplaceNewerEpochGeneration() {
    val registry = TicketVideoClientDeliveryRegistry<String>()
    val epochSeven = state(epoch = 7L)
    val epochEight = state(epoch = 8L)
    registry.add("relay", epochSeven)
    registry.replace("relay", epochEight)

    val staleEpochSeven = state(epoch = 7L)
    val replaced = registry.replace("relay", staleEpochSeven)

    assertTrue(replaced == null)
    assertTrue(staleEpochSeven.snapshot().closed)
    assertTrue(registry.current("relay") === epochEight)
    assertFalse(epochEight.snapshot().closed)
  }

  private fun state(epoch: Long): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = epoch,
      maxQueuedFrames = 4,
      maxQueuedBytes = 1024,
      pendingMaxAgeMillis = 150L,
      slowCloseMillis = 250L
    )
  }
}
