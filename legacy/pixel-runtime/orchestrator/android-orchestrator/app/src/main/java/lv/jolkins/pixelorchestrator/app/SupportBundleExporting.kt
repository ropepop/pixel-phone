package lv.jolkins.pixelorchestrator.app

import java.io.File
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1

interface SupportBundleExporting {
  suspend fun export(
    config: StackConfigV1,
    state: StackStateV1,
    includeSecrets: Boolean
  ): File
}
