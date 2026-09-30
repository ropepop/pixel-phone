package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlinx.serialization.json.*

/** Float raw bits preserve NaN/infinity and the original single-precision comparisons. */
internal object NativeTouchBrightness {
  init {System.loadLibrary("pixel_health")}
  private external fun decide(operation: String,payload: String): String
  private fun state(value:ScreenBrightnessState?)=value?.let {buildJsonObject {
    put("mode",it.mode);put("value",it.value);put("displayBits",it.displayPercentage?.toRawBits());put("panelPath",it.panelPath)
    put("panelBrightness",it.panelBrightness);put("panelActualBrightness",it.panelActualBrightness)
    put("panelMaxBrightness",it.panelMaxBrightness);put("panelBacklightPower",it.panelBacklightPower)
  }} ?: JsonNull
  fun call(operation:String,value:ScreenBrightnessState?=null,expected:ScreenBrightnessState?=null,target:Int=0,panelOnly:Boolean=false):JsonElement =
    Json.parseToJsonElement(decide(operation,buildJsonObject {
      put("state",state(value));put("expected",state(expected));put("target",target);put("panelOnly",panelOnly)
    }.toString()))
  fun test(operation:String,value:ScreenBrightnessState,expected:ScreenBrightnessState?=null,target:Int=0,panelOnly:Boolean=false)=
    call(operation,value,expected,target,panelOnly).jsonPrimitive.boolean
  fun number(operation:String,target:Int,state:ScreenBrightnessState?=null)=call(operation,state,target=target).jsonPrimitive.int
  fun fallback(value:ScreenBrightnessState,remote:ScreenBrightnessState?):ScreenBrightnessState {
    val result=call("remote_fallback",value,remote).jsonObject
    fun integer(key:String)=result[key]?.jsonPrimitive?.intOrNull
    return ScreenBrightnessState(integer("mode"),integer("value"),integer("displayBits")?.let(Float::fromBits),
      result["panelPath"]?.jsonPrimitive?.contentOrNull,integer("panelBrightness"),integer("panelActualBrightness"),integer("panelMaxBrightness"),integer("panelBacklightPower"))
  }
}
