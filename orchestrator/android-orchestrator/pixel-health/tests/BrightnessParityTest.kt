package lv.jolkins.pixelorchestrator.app.phoneautomation
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class BrightnessParityTest {
  @Test fun visibleRestoreBlankPanelFallbackAndNumericExtremesMatchFrozenOwner() {
    var comparisons=0
    val random=Random(42)
    val ints=listOf(null,Int.MIN_VALUE,-1,0,1,2,3,20,51,100,127,255,788,3939,Int.MAX_VALUE)
    val floats=listOf(null,Float.NEGATIVE_INFINITY,-1f,0f,0.49f,0.5f,0.51f,1f,20f,49.5f,50f,100f,Float.POSITIVE_INFINITY,Float.NaN)
    fun state()=ScreenBrightnessState(ints[random.nextInt(ints.size)],ints[random.nextInt(ints.size)],floats[random.nextInt(floats.size)],
      if(random.nextBoolean())"/sys/fixture" else null,ints[random.nextInt(ints.size)],ints[random.nextInt(ints.size)],ints[random.nextInt(ints.size)],ints[random.nextInt(ints.size)])
    fun compare(old:()->Any?,next:()->Any?) {
      val a=runCatching(old);val b=runCatching(next)
      assertEquals(a.getOrNull(),b.getOrNull());assertEquals(a.exceptionOrNull()?.javaClass,b.exceptionOrNull()?.javaClass)
      assertEquals(a.exceptionOrNull()?.message,b.exceptionOrNull()?.message);comparisons++
    }
    repeat(20000) {
      val s=state();val expected=state();val target=ints[random.nextInt(ints.size)]?:0
      for(panelOnly in listOf(false,true)) {
        compare({LegacyBrightnessVerifier.run{s.matchesTargetLenient(target,panelOnly)}},{NativeTouchBrightness.test("target_matches",s,target=target,panelOnly=panelOnly)})
        compare({LegacyBrightnessVerifier.run{s.matchesRestoredStateLenient(expected,panelOnly)}},{NativeTouchBrightness.test("restored_matches",s,expected,panelOnly=panelOnly)})
      }
      compare({LegacyBrightnessVerifier.run{s.panelPercentMatches(target)}},{NativeTouchBrightness.test("panel_matches",s,target=target)})
      compare({LegacyBrightnessVerifier.run{s.hasPanelBrightnessData()}},{NativeTouchBrightness.test("has_panel",s)})
      compare({LegacyBrightnessVerifier.run{s.hasVisiblePanelBrightnessData()}},{NativeTouchBrightness.test("visible_panel",s)})
      compare({LegacyBrightnessVerifier.run{s.isPanelSleepBrightnessState()}},{NativeTouchBrightness.test("panel_sleep",s)})
      compare({LegacyBrightnessVerifier.run{s.visiblePanelFallbackPercent()}},{NativeTouchBrightness.call("fallback_percent",s).jsonPrimitive.int})
      compare({LegacyBrightnessVerifier.run{s.withRemotePanelFallback(expected)}},{NativeTouchBrightness.fallback(s,expected)})
      compare({LegacyBrightnessVerifier.run{s.withRemotePanelFallback(null)}},{NativeTouchBrightness.fallback(s,null)})
      compare({s.mode==expected.mode&&s.value==expected.value&&(s.displayPercentage?:0f)>0.5f},{NativeTouchBrightness.test("android_unchanged",s,expected)})
    }
    for(percent in listOf(Int.MIN_VALUE,-1,0,1,2,19,20,50,99,100,101,Int.MAX_VALUE)) {
      compare({LegacyScreenBrightnessControl.legacySystemValue(percent)},{ScreenBrightnessControl.legacySystemValue(percent)})
      compare({LegacyScreenBrightnessControl.percentFromSystemValue(percent)},{ScreenBrightnessControl.percentFromSystemValue(percent)})
      for(max in ints.filterNotNull()) compare({LegacyScreenBrightnessControl.panelValueFromPercent(percent,max)},{ScreenBrightnessControl.panelValueFromPercent(percent,max)})
    }
    println("BRIGHTNESS_VERIFICATION_PARITY_OK comparisons=$comparisons")
  }
}
