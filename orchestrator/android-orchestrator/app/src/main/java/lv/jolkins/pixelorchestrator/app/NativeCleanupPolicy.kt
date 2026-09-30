package lv.jolkins.pixelorchestrator.app
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal object NativeCleanupPolicy {
  private val json=Json
  init {System.loadLibrary("pixel_health")}
  private external fun decide(operation:String,payload:String):String
  fun call(operation:String,args:JsonElement):JsonElement=json.parseToJsonElement(decide(operation,args.toString()))
  inline fun <reified T> value(operation:String,args:JsonElement):T=Json.decodeFromJsonElement(call(operation,args))
  fun finishStatus(failures:Int,dryRun:Boolean)=call("finish_status",buildJsonObject{put("failures",failures);put("dryRun",dryRun)}).jsonPrimitive.content
}
