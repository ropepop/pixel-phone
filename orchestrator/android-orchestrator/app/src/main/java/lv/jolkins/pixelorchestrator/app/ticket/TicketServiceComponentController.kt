package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.supervisor.AutoStartAwareComponentController
import lv.jolkins.pixelorchestrator.supervisor.ComponentController

class TicketServiceComponentController(
  private val delegate: ComponentController,
  private val settingsStore: TicketServiceSettingsStore
) : ComponentController, AutoStartAwareComponentController {
  override val name: String
    get() = delegate.name

  override suspend fun start(): Boolean = delegate.start()

  override suspend fun stop(): Boolean = delegate.stop()

  override suspend fun health(): Boolean = delegate.health()

  override suspend fun shouldAutoStart(): Boolean = settingsStore.load().enabled
}
