package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketServicePreferencesStoreTest {
  @Test
  fun toggleDefaultsOffAndPersistsAcrossStoreInstances() {
    val backend = InMemoryTicketServicePreferencesBackend()

    val initial = TicketServicePreferencesStore(backend).load()
    assertFalse(initial.enabled)
    assertEquals(TicketServiceRuntimeState.DISABLED, initial.runtimeState)

    val firstStore = TicketServicePreferencesStore(backend)
    firstStore.setEnabled(true)
    firstStore.recordEnsureResult(
      reason = "test",
      success = true,
      result = "ready",
      localServerReachable = true,
      tunnelReady = true,
      componentStatus = "running"
    )

    val ready = TicketServicePreferencesStore(backend).load()
    assertTrue(ready.enabled)
    assertEquals(TicketServiceRuntimeState.READY, ready.runtimeState)
    assertTrue(ready.lastEnsureSucceeded)
    assertTrue(ready.localServerReachable)
    assertTrue(ready.tunnelReady)
    assertEquals("running", ready.componentStatus)

    TicketServicePreferencesStore(backend).setEnabled(false)

    val disabled = TicketServicePreferencesStore(backend).load()
    assertFalse(disabled.enabled)
    assertEquals(TicketServiceRuntimeState.DISABLED, disabled.runtimeState)
    assertFalse(disabled.localServerReachable)
    assertFalse(disabled.tunnelReady)
  }

  @Test
  fun degradedReadinessRecordsLocalAndTunnelState() {
    val backend = InMemoryTicketServicePreferencesBackend()
    val store = TicketServicePreferencesStore(backend)

    store.setEnabled(true)
    store.recordEnsureResult(
      reason = "periodic",
      success = false,
      result = "Ticket tunnel is not ready",
      localServerReachable = true,
      tunnelReady = false,
      componentStatus = "running"
    )

    val snapshot = store.load()
    assertEquals(TicketServiceRuntimeState.DEGRADED, snapshot.runtimeState)
    assertTrue(snapshot.localServerReachable)
    assertFalse(snapshot.tunnelReady)
    assertEquals("Ticket tunnel is not ready", snapshot.runtimeDetail)
  }

  @Test
  fun periodicEnsureDoesNotDowngradeAnAlreadyReadyServiceToStarting() {
    val backend = InMemoryTicketServicePreferencesBackend()
    val store = TicketServicePreferencesStore(backend)

    store.setEnabled(true)
    store.recordEnsureResult(
      reason = "initial",
      success = true,
      result = "ready",
      localServerReachable = true,
      tunnelReady = true,
      componentStatus = "running"
    )

    val snapshot = store.recordEnsureStarted("periodic")

    assertEquals(TicketServiceRuntimeState.READY, snapshot.runtimeState)
    assertEquals("Local ticket server and tunnel are ready", snapshot.runtimeDetail)
    assertTrue(snapshot.lastEnsureSucceeded)
    assertTrue(snapshot.localServerReachable)
    assertTrue(snapshot.tunnelReady)
  }
}

private class InMemoryTicketServicePreferencesBackend : TicketServicePreferencesBackend {
  private val values = linkedMapOf<String, Any>()

  override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
    return values[key] as? Boolean ?: defaultValue
  }

  override fun getString(key: String, defaultValue: String): String {
    return values[key] as? String ?: defaultValue
  }

  override fun getLong(key: String, defaultValue: Long): Long {
    return values[key] as? Long ?: defaultValue
  }

  override fun applyMutations(mutations: Map<String, Any>) {
    values.putAll(mutations)
  }
}
