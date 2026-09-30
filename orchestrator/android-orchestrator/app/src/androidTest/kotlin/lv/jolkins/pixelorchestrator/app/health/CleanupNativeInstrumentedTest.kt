package lv.jolkins.pixelorchestrator.app.health
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.app.*
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native caller policy plus an existing report read. No cleanup command or file mutation. */
@RunWith(AndroidJUnit4::class)
class CleanupNativeInstrumentedTest {
  @Test fun installedCleanupPolicyKeepsProtectedOrderAndReadsExistingHealthOnly()=runBlocking {
    val json=Json {encodeDefaults=true;ignoreUnknownKeys=true}
    val protected=listOf(CleanupPathRecord("first"," /fixture/a "),CleanupPathRecord("duplicate","/fixture/a"),CleanupPathRecord("blank","\u2007"))
    assertEquals(listOf(CleanupPathRecord("first","/fixture/a")),NativeCleanupPolicy.value<List<CleanupPathRecord>>("protected_paths",buildJsonObject{put("paths",json.encodeToJsonElement(protected))}))
    val raw=NativeCleanupPolicy.call("parse_script",buildJsonObject{put("stdout","CANDIDATE\tfixture\t12\t/fixture\teligible\nOBSERVE\truntime_log_total\t+42\t/fixture\n")}).jsonObject
    assertEquals(12L,raw.getValue("candidates").jsonArray.single().jsonObject.getValue("bytes").jsonPrimitive.long)
    assertEquals(42L,raw.getValue("observations").jsonObject.getValue("runtime_log_total").jsonPrimitive.long)
    assertEquals("failed",NativeCleanupPolicy.finishStatus(1,true));assertEquals("dry_run",NativeCleanupPolicy.finishStatus(0,true))
    val report=CleanupReport(trigger="manual",dryRun=false,status="completed",startedAt="2026-09-30T00:00:00Z",finishedAt="2026-09-30T00:00:00Z",deletedPaths=listOf(CleanupPathRecord("fixture","/fixture",bytes=12)))
    val projected=NativeCleanupPolicy.value<CleanupReport>("report",json.encodeToJsonElement(report))
    assertEquals(12L,projected.summary.deletedBytes)
    assertEquals(emptyList<CleanupPathRecord>(),NativeCleanupPolicy.value<CleanupReport>("durable_report",json.encodeToJsonElement(projected)).deletedPaths)
    val actual=RuntimeCleanupComponentController(SuRootExecutor(),json).moduleHealthState()
    assertFalse("Existing cleanup report must be readable",actual.details["failure_reason"] in listOf("report_probe_failed","no_report","report_body_missing","report_parse_failed","report_time_invalid"))
    InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
      putString("cleanup_native_readonly_result","CLEANUP_NATIVE_OK healthy=${actual.healthy} reason=${actual.details["failure_reason"]} no_cleanup_invocation=true")
    })
  }
}
