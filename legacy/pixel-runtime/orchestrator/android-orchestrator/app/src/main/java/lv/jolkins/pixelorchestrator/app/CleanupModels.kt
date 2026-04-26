package lv.jolkins.pixelorchestrator.app

import kotlinx.serialization.Serializable

@Serializable
enum class CleanupTrigger {
  MANUAL,
  SCHEDULED;

  fun wireValue(): String = name.lowercase()

  companion object {
    fun fromWire(value: String?): CleanupTrigger =
      entries.firstOrNull { it.wireValue() == value?.trim()?.lowercase() } ?: MANUAL
  }
}

@Serializable
enum class CleanupReportStatus {
  COMPLETED,
  DRY_RUN,
  SKIPPED,
  FAILED;

  fun wireValue(): String = name.lowercase()
}

@Serializable
data class CleanupRetentionPolicy(
  val artifactHours: Int = 72,
  val logHours: Int = 168
)

@Serializable
data class CleanupPathRecord(
  val category: String,
  val path: String,
  val bytes: Long = 0,
  val detail: String = ""
)

@Serializable
data class CleanupCategorySummary(
  val category: String,
  val candidates: Int = 0,
  val candidateBytes: Long = 0,
  val deleted: Int = 0,
  val deletedBytes: Long = 0,
  val skipped: Int = 0,
  val failures: Int = 0
)

@Serializable
data class CleanupSummary(
  val protectedCount: Int = 0,
  val candidateCount: Int = 0,
  val candidateBytes: Long = 0,
  val deletedCount: Int = 0,
  val deletedBytes: Long = 0,
  val skippedCount: Int = 0,
  val failureCount: Int = 0,
  val categories: List<CleanupCategorySummary> = emptyList()
)

@Serializable
data class CleanupReport(
  val schema: Int = 1,
  val trigger: String,
  val dryRun: Boolean,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val retention: CleanupRetentionPolicy = CleanupRetentionPolicy(),
  val summary: CleanupSummary = CleanupSummary(),
  val protectedPaths: List<CleanupPathRecord> = emptyList(),
  val candidates: List<CleanupPathRecord> = emptyList(),
  val deletedPaths: List<CleanupPathRecord> = emptyList(),
  val skippedPaths: List<CleanupPathRecord> = emptyList(),
  val failurePaths: List<CleanupPathRecord> = emptyList()
)

internal data class CleanupScheduleResult(
  val success: Boolean,
  val scheduledAtMillis: Long = 0,
  val reason: String = ""
)
