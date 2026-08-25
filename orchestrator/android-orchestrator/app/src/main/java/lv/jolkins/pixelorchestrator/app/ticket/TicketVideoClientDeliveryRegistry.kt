package lv.jolkins.pixelorchestrator.app.ticket

/** Atomic client-to-delivery-generation registry used to reject obsolete async effects. */
internal class TicketVideoClientDeliveryRegistry<Client : Any> {
  private val states = mutableMapOf<Client, TicketVideoClientDeliveryState>()

  @Synchronized
  fun add(client: Client, state: TicketVideoClientDeliveryState) {
    states.put(client, state)?.close()
  }

  @Synchronized
  fun remove(client: Client): TicketVideoClientDeliveryState? = states.remove(client)

  @Synchronized
  fun current(client: Client): TicketVideoClientDeliveryState? = states[client]

  @Synchronized
  fun isCurrent(client: Client, expectedState: TicketVideoClientDeliveryState): Boolean {
    return states[client] === expectedState
  }

  @Synchronized
  fun replace(
    client: Client,
    replacement: TicketVideoClientDeliveryState
  ): TicketVideoClientDeliveryState? {
    val previous = states[client] ?: run {
      replacement.close()
      return null
    }
    if (replacement.expectedEpoch < previous.expectedEpoch) {
      replacement.close()
      return null
    }
    states[client] = replacement
    previous.close()
    return previous
  }

  @Synchronized
  fun replaceAll(replacement: () -> TicketVideoClientDeliveryState) {
    states.keys.toList().forEach { client ->
      val previous = states.getValue(client)
      states[client] = replacement()
      previous.close()
    }
  }

  @Synchronized
  fun ifCurrent(
    client: Client,
    expectedState: TicketVideoClientDeliveryState,
    action: () -> Unit
  ): Boolean {
    if (states[client] !== expectedState) return false
    action()
    return true
  }
}
