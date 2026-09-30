package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed pure policy and read-only device state; no brightness, touch or power mutation. */
@RunWith(AndroidJUnit4::class)
class BrightnessNativeInstrumentedTest {
  @Test fun installedBrightnessVerificationPreservesRawFloatAndReadsCurrentPanelWithoutMutation()=runBlocking {
    val visible=ScreenBrightnessState(0,51,20f,panelBrightness=788,panelActualBrightness=788,panelMaxBrightness=3939,panelBacklightPower=0)
    assertTrue(NativeTouchBrightness.test("target_matches",visible,target=20))
    assertTrue(NativeTouchBrightness.test("restored_matches",visible,visible))
    val blank=visible.copy(value=0,displayPercentage=0f,panelBrightness=0,panelActualBrightness=0,panelBacklightPower=4)
    assertTrue(NativeTouchBrightness.test("panel_sleep",blank));assertFalse(NativeTouchBrightness.test("target_matches",blank,target=20))
    assertEquals(visible,NativeTouchBrightness.fallback(visible.copy(displayPercentage=null),visible))
    assertTrue(NativeTouchBrightness.fallback(visible.copy(displayPercentage=Float.NaN),visible).displayPercentage!!.isNaN())
    assertEquals(51,ScreenBrightnessControl.legacySystemValue(20));assertEquals(788,ScreenBrightnessControl.panelValueFromPercent(20,3939))
    val result=SuRootExecutor().runScript(ScreenBrightnessControl.buildReadStateScript())
    assertTrue("Read-only actual panel probe failed",result.ok)
    val state=ScreenBrightnessControl.parseState(result.stdout)
    assertNotNull("Actual panel state is unavailable",state)
    state!!
    assertEquals(state.panelMaxBrightness!=null&&(state.panelActualBrightness!=null||state.panelBrightness!=null),NativeTouchBrightness.test("has_panel",state))
    assertEquals((state.panelActualBrightness?:state.panelBrightness?:Int.MIN_VALUE)>2,NativeTouchBrightness.test("visible_panel",state))
    if(state.panelBacklightPower==4) assertTrue(NativeTouchBrightness.test("panel_sleep",state))
    InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
      putString("brightness_native_readonly_result","BRIGHTNESS_NATIVE_OK panel_power=${state.panelBacklightPower} native_panel_sleep=${NativeTouchBrightness.test("panel_sleep",state)} no_mutation=true physical_light_unverified=true")
    })
  }
}
