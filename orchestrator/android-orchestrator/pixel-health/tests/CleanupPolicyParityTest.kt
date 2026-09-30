package lv.jolkins.pixelorchestrator.app
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.Random
import kotlin.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.rootexec.*
import lv.jolkins.pixelorchestrator.runtimeinstaller.*
import org.junit.Assert.*
import org.junit.Test

class CleanupPolicyParityTest {
  private val json=Json {encodeDefaults=true;ignoreUnknownKeys=true}
  private var stdout=""
  private var dispatch:(String)->String={stdout}
  private val root=Proxy.newProxyInstance(RootExecutor::class.java.classLoader,arrayOf(RootExecutor::class.java)){_,method,args->
    when(method.name.substringBefore('-')){"run","runScript"-> {val command=args?.firstOrNull()?.toString().orEmpty();RootResult(0,dispatch(command),"",command,0)};"isRootAvailable"->true;else->error("unexpected fixture effect: ${method.name}")}
  } as RootExecutor
  private inline fun <reified T> unused()=Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,method,_->error("unexpected fixture effect: ${method.name}")} as T
  private fun invoke(owner:Any,name:String,vararg args:Any?):Any? {
    val method=owner.javaClass.declaredMethods.single {it.name==name&&it.parameterCount==args.size};method.isAccessible=true
    return try{method.invoke(owner,*args)}catch(e:InvocationTargetException){throw e.targetException}
  }
  @Test fun fullFormerCleanupCallerProtocolReportsAndRetentionMatchNativeOwner() {
    val old=LegacyNightlyCleanupSupport(StackStore(),root,unused<RuntimeInstallerControl>(),unused<AssetProvider>(),json)
    val next=NightlyCleanupSupport(StackStore(),root,unused<RuntimeInstallerControl>(),unused<AssetProvider>(),json)
    val random=Random(42);var comparisons=0
    fun compare(a:Any?,b:Any?){assertEquals(a,b);comparisons++}
    val bytes=listOf(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE)
    val categories=listOf("a","b","","\u2007","\uE000","\uD83D\uDE00")
    fun records()=List(random.nextInt(6)){CleanupPathRecord(categories[random.nextInt(categories.size)],"/fixture/${random.nextInt(5)}",bytes[random.nextInt(bytes.size)],if(random.nextBoolean())"reason" else "")}
    repeat(2000) {
      val protected=records();val candidates=records();val deleted=records();val skipped=records();val failures=records()
      val payload=buildJsonObject{put("protectedPaths",json.encodeToJsonElement(protected));put("candidates",json.encodeToJsonElement(candidates));put("deletedPaths",json.encodeToJsonElement(deleted));put("skippedPaths",json.encodeToJsonElement(skipped));put("failurePaths",json.encodeToJsonElement(failures))}
      compare(invoke(old,"summarize",protected,candidates,deleted,skipped,failures),NativeCleanupPolicy.value<CleanupSummary>("summary",payload))
      val status=CleanupReportStatus.entries[random.nextInt(4)];val trigger=CleanupTrigger.entries[random.nextInt(2)];val dry=random.nextBoolean();val started=Instant.parse("2026-09-30T00:00:00Z")
      val oldReport=invoke(old,"buildReport",trigger,dry,status,started,protected,candidates,deleted,skipped,failures) as CleanupReport
      val nextReport=invoke(next,"buildReport",trigger,dry,status,started,protected,candidates,deleted,skipped,failures) as CleanupReport
      compare(oldReport.copy(finishedAt="fixed"),nextReport.copy(finishedAt="fixed"))
      compare(invoke(old,"buildMessage",oldReport),NativeCleanupPolicy.call("outcome",json.encodeToJsonElement(oldReport)).jsonObject.getValue("message").jsonPrimitive.content)
      val protocol=buildString{
        for((type,entries) in listOf("CANDIDATE" to candidates,"DELETE" to deleted,"SKIP" to skipped,"FAIL" to failures)) for(record in entries)
          append("$type\t${record.category}\t${record.bytes}\t${record.path}\t${record.detail}\r\n")
        append("OBSERVE\tsuperuser_log_db\t${bytes[it%bytes.size]}\t/fixture\nOBSERVE\truntime_log_total\t+42\t/fixture\nmalformed\tline\n")
      }
      val oldOutput=invoke(old,"parseScriptOutput",protocol);val nextOutput=invoke(next,"parseScriptOutput",protocol)
      compare(oldOutput.toString(),nextOutput.toString())
      val oldFrequent=invoke(old,"buildFrequentMaintenanceReport",started,status,dry,"fixture",oldOutput) as FrequentMaintenanceReport
      val nextFrequent=invoke(next,"buildFrequentMaintenanceReport",started,status,dry,"fixture",nextOutput) as FrequentMaintenanceReport
      compare(oldFrequent.copy(finishedAt="fixed"),nextFrequent.copy(finishedAt="fixed"))
    }
    val urls=listOf(""," ","\u2007","/fixture"," file:///fixture ","FILE:///fixture","file://relative","https://example.invalid")
    for(url in urls) compare(invoke(old,"localArtifactPath",url),NativeCleanupPolicy.call("local_path",buildJsonObject{put("url",url)}).jsonPrimitive.contentOrNull)
    val malformed=listOf("CANDIDATE\ta\t+1\t/p", "DELETE\tb\t1e3\t/p\t\textra\tend", "SKIP\tc\t 2\t/p\t ","FAIL\tc\t9223372036854775808\t/p\n", "OBSERVE\tc\t-1\t/p\rOBSERVE\tc\t+2\t/p", "UNKNOWN\tx\t1\t/p")
    for(line in malformed) compare(invoke(old,"parseScriptOutput",line).toString(),invoke(next,"parseScriptOutput",line).toString())
    val paths=listOf("/x/site-notifier-20260930T010000Z/a","/x/site_notifier-20260930T010000Z/b","/x/site-notifier-20260929T010000Z/a","/x/site-notifier-20260928T010000Z/a","/x/site-notifier-٢٠٢٦٠٩٣٠T010000Z/a","/invalid","/x/site-notifier-20260929T010000Z/b")
    val key:(String)->String?={Regex("""site[-_]notifier-\d{8}T\d{6}Z""").find(it)?.value?.replace('_','-')}
    for(limit in 0..paths.size) {
      val input=paths.take(limit)
      val previous=invoke(old,"retainNewestGroups",input,2,key) as List<*>
      val result=NativeCleanupPolicy.value<List<CleanupPathRecord>>("recent_paths",buildJsonObject{put("notifier",json.encodeToJsonElement(input));put("runtime",JsonArray(emptyList()));put("build",JsonArray(emptyList()))})
      compare(previous,result.map {it.path})
    }
    val completion=object:Continuation<Any?>{override val context=EmptyCoroutineContext;override fun resumeWith(result:Result<Any?>){error("Fixture reads must be synchronous")}}
    for(rollback in listOf(false,true)) for(version in listOf("","\u2007","fixture")) for(url in urls) for(empty in listOf(false,true)) {
      val manifest=ArtifactManifest(manifestVersion=version,artifacts=if(empty) emptyList() else listOf(ArtifactEntry("fixture",url,"","fixture")))
      stdout=json.encodeToString(ArtifactManifest.serializer(),manifest)
      val previous=runCatching{if(rollback)invoke(old,"loadRuntimeManifestIfPresent","/fixture",completion)else invoke(old,"loadRuntimeManifest",completion)}
      val current=runCatching{if(rollback)invoke(next,"loadRuntimeManifestIfPresent","/fixture",completion)else invoke(next,"loadRuntimeManifest",completion)}
      if(version=="fixture" && (empty&&rollback || !empty&&url in listOf("/fixture"," file:///fixture "))) {
        assertTrue("Former manifest fixture did not reach success",previous.isSuccess);assertTrue(current.isSuccess)
      }
      compare(previous.getOrNull(),current.getOrNull());compare(previous.exceptionOrNull()?.javaClass,current.exceptionOrNull()?.javaClass);compare(previous.exceptionOrNull()?.message,current.exceptionOrNull()?.message)
    }
    for(current in listOf(""," ","/fixture/a","/fixture/b")) for(releases in listOf(emptyList(),listOf("/fixture/a"),listOf("/fixture/a","/fixture/b"),listOf("/fixture/a","/fixture/a"))) {
      stdout=(if(current.isEmpty())""else "CURRENT\t$current\n")+releases.joinToString(""){"RELEASE\t$it\n"}
      compare(invoke(old,"queryReleasePaths","/fixture",completion).toString(),invoke(next,"queryReleasePaths","/fixture",completion).toString())
      compare(invoke(old,"protectedReleasePaths",StackConfigV1(),completion),invoke(next,"protectedReleasePaths",StackConfigV1(),completion))
    }
    for(count in 0..paths.size) {
      stdout=paths.take(count).joinToString("\n")
      compare(invoke(old,"protectedRecentTermuxArtifacts",completion),invoke(next,"protectedRecentTermuxArtifacts",completion))
    }
    for(duplicate in listOf(false,true)) for(padding in listOf(""," \u2007")) {
      val entries=listOf(ArtifactEntry("a",padding+"/fixture/shared"+padding,"","a"),ArtifactEntry("b",if(duplicate)"/fixture/shared"else"file:///fixture/other","","b"))
      dispatch={command->when{
        command.contains("runtime-manifest")->json.encodeToString(ArtifactManifest.serializer(),ArtifactManifest(manifestVersion="fixture",artifacts=entries))
        command.contains("/components/")->{val component=Regex("/components/([^/]+)/").find(command)!!.groupValues[1];json.encodeToString(ComponentReleaseManifest.serializer(),ComponentReleaseManifest(componentId=component,releaseId="fixture",artifacts=entries))}
        command.contains("current_target")->"CURRENT\t/fixture/current\nRELEASE\t/fixture/current\nRELEASE\t/fixture/previous\n"
        else->paths.joinToString("\n")
      }}
      compare(invoke(old,"buildProtectedPaths",StackConfigV1(),completion),invoke(next,"buildProtectedPaths",StackConfigV1(),completion))
    }
    dispatch={stdout}
    println("CLEANUP_CALLER_PARITY_OK comparisons=$comparisons")
  }
  @Test fun activeCleanupHealthFreshnessProtocolAndOutcomeMatchFrozenOwner()=runBlocking {
    val old=LegacyRuntimeCleanupComponentController(root,json);var comparisons=0
    val now=Instant.parse("2026-09-30T00:00:00Z")
    for(age in listOf(-1L,0L,1L,691200L,691201L)) for(status in listOf("completed","dry_run","skipped","failed","")) for(dry in listOf(false,true)) for(path in listOf("","/fixture")) {
      val report=CleanupReport(trigger="manual",dryRun=dry,status=status,startedAt=now.minusSeconds(age).toString(),finishedAt=now.minusSeconds(age).toString(),summary=CleanupSummary(deletedCount=2,deletedBytes=1024))
      stdout="REPORT_PATH\t$path\nREPORT_BODY\n"+json.encodeToString(CleanupReport.serializer(),report)
      val expected=old.moduleHealthState()
      val actual=NativeCleanupPolicy.value<lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState>("health_result",buildJsonObject{put("report",json.encodeToJsonElement(report));put("path",path);put("age",age)})
      assertEquals(expected,actual);comparisons++
      val protocol=NativeCleanupPolicy.call("health_protocol",buildJsonObject{put("ok",true);put("stdout",stdout);put("stderr","")}).jsonObject
      assertEquals(path,protocol.getValue("path").jsonPrimitive.content);assertEquals("",protocol.getValue("reason").jsonPrimitive.content);comparisons++
    }
    println("CLEANUP_HEALTH_PARITY_OK comparisons=$comparisons")
  }
}
