package lv.jolkins.pixelorchestrator.app.ticket;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real JNI compared with the retained Java release; synthetic probes only, no device actions. */
public class ControlParityTest {
  private int cases;
  private int signatures;
  private int bounds;
  private final Set<String> observed = new TreeSet<>();

  private void sameFixtureSalt() throws Exception {
    Field old = LegacyTicketControlCodeVisualClassifier.class.getDeclaredField("VISUAL_SIGNATURE_SALT");
    Field now = TicketControlCodeVisualClassifier.class.getDeclaredField("VISUAL_SIGNATURE_SALT");
    old.setAccessible(true); now.setAccessible(true);
    System.arraycopy((byte[])now.get(null), 0, (byte[])old.get(null), 0, 32);
  }
  private static void paint(int[] p, int width, int left, int top, int right, int bottom, int color) {
    for (int y=top;y<bottom;y++) for (int x=left;x<right;x++) p[y*width+x]=color;
  }
  private static int[] detail() {
    int[] p=new int[48*72]; Arrays.fill(p,0xffb0b0b0);
    paint(p,48,0,0,48,8,0xff30353a); paint(p,48,1,8,47,14,0xffbf4020);
    for(int y=14;y<34;y++)for(int x=8;x<40;x++)p[y*48+x]=((x+y)%2==0)?-1:0xff202020;
    paint(p,48,7,36,41,40,-1);
    for(int x=9;x<39;x+=3)p[38*48+x]=0xff202020;
    return p;
  }
  private static int[] generated(int top, int dark, int light) {
    int[] p=detail();paint(p,48,7,top,41,top+5,0xff000000|dark*0x010101);
    for(int[] point:new int[][]{{20,2},{36,1},{38,1},{37,2}})p[(top+point[1])*48+point[0]]=0xff000000|light*0x010101;
    return p;
  }
  private static int[] doubled(int[] p) {
    int[] out=new int[96*144];for(int y=0;y<144;y++)for(int x=0;x<96;x++)out[y*96+x]=p[(y/2)*48+x/2];return out;
  }
  private static int[] transformed(int[] p,int brightness,boolean swap) {
    int[] result=p.clone();for(int i=0;i<result.length;i++) {
      int r=(p[i]>>>16)&255,g=(p[i]>>>8)&255,b=p[i]&255;
      r=Math.max(0,Math.min(255,r+brightness));g=Math.max(0,Math.min(255,g+brightness));b=Math.max(0,Math.min(255,b+brightness));
      result[i]=(p[i]&0xff000000)|((swap?b:r)<<16)|(g<<8)|(swap?r:b);
    }return result;
  }
  private void same(String label,String a,String b) { assertEquals(label+" case "+cases,a,b); }
  private void low(int[] p) {
    same("classification",LegacyTicketControlCodeVisualClassifier.classify(p),TicketControlCodeVisualClassifier.classify(p));
    same("activated",LegacyTicketControlCodeVisualClassifier.classifyForActivatedTicket(p),TicketControlCodeVisualClassifier.classifyForActivatedTicket(p));
    same("cleanup",LegacyTicketControlCodeVisualClassifier.classifyForCleanup(p),TicketControlCodeVisualClassifier.classifyForCleanup(p));
    String expected=LegacyTicketControlCodeVisualClassifier.generatedResultCloseBounds(p),actual=TicketControlCodeVisualClassifier.generatedResultCloseBounds(p);
    same("close target",expected,actual);if(!actual.isEmpty())bounds++;
    same("slider",LegacyTicketControlCodeVisualClassifier.registrationSliderBounds(p),TicketControlCodeVisualClassifier.registrationSliderBounds(p));
    expected=LegacyTicketControlCodeVisualClassifier.ticketCodeVisualSignature(p);actual=TicketControlCodeVisualClassifier.ticketCodeVisualSignature(p);
    same("salted code identity",expected,actual);if(!actual.isEmpty())signatures++;
    observed.add(TicketControlCodeVisualClassifier.classify(p));observed.add(TicketControlCodeVisualClassifier.classifyForCleanup(p));
    cases++;
  }
  private void high(int[] p) {
    same("high cleanup",LegacyTicketControlCodeVisualClassifier.classifyForCleanupHighResolution(p),TicketControlCodeVisualClassifier.classifyForCleanupHighResolution(p));
    same("high close target",LegacyTicketControlCodeVisualClassifier.generatedResultCloseBoundsHighResolution(p),TicketControlCodeVisualClassifier.generatedResultCloseBoundsHighResolution(p));
    same("high salted identity",LegacyTicketControlCodeVisualClassifier.ticketCodeVisualSignatureHighResolution(p),TicketControlCodeVisualClassifier.ticketCodeVisualSignatureHighResolution(p));
    same("submit layout",LegacyTicketControlCodeVisualClassifier.classifySubmitLayout(p),TicketControlCodeVisualClassifier.classifySubmitLayout(p));
    same("input target",LegacyTicketControlCodeVisualClassifier.submitInputBounds(p),TicketControlCodeVisualClassifier.submitInputBounds(p));
    same("button target",LegacyTicketControlCodeVisualClassifier.submitButtonBounds(p),TicketControlCodeVisualClassifier.submitButtonBounds(p));
    observed.add(TicketControlCodeVisualClassifier.classifySubmitLayout(p));cases++;
  }
  @Test public void generatedCleanupAndRegistrationGeometryMatchActualNativeCore() throws Exception {
    sameFixtureSalt();List<int[]> images=new ArrayList<>();images.add(detail());
    for(int top=18;top<=44;top++)for(int dark:new int[]{79,80,81,99,100,101})for(int light:new int[]{144,145,146,174,175,176,255}) images.add(generated(top,dark,light));
    int[] slider=detail();paint(slider,48,6,45,44,51,0xffffa000);paint(slider,48,4,45,10,51,0xff202020);images.add(slider);
    int[] list=detail();paint(list,48,3,30,43,35,0xffffa000);images.add(list);
    int[] registered=detail();paint(registered,48,3,43,43,48,0xffffa000);images.add(registered);
    int[] current=detail();paint(current,48,3,42,35,47,0xffdae5e5);paint(current,48,38,43,43,47,0xffffa000);images.add(current);
    int[] popup=detail();paint(popup,48,8,30,40,45,-1);paint(popup,48,31,39,42,44,0xffffa000);images.add(popup);
    int[] darkPopup=detail();paint(darkPopup,48,4,26,44,43,0xff202020);paint(darkPopup,48,4,34,44,40,0xff2050a0);images.add(darkPopup);
    for(int color:new int[]{0,-1,0xff000000,0xff505050,0xffb0b0b0,0xffffa000}){int[] p=new int[48*72];Arrays.fill(p,color);images.add(p);}
    Random random=new Random(847151);
    for(int i=0;i<images.size();i++) {
      int[] p=images.get(i);low(p);high(doubled(p));
      if(i%19==0) {
        low(transformed(p,0,true));low(transformed(p,-12,false));low(transformed(p,12,false));
        int[] noisy=p.clone();for(int n=0;n<12;n++)noisy[random.nextInt(noisy.length)]^=0x00ffffff;low(noisy);
      }
    }
    assertTrue(observed.toString(),observed.containsAll(Set.of("unknown","raw_ticket","generated","control_popup","ticket_list_with_registration_button")));
    assertTrue("generated close targets must be exercised",bounds>100);
    assertTrue("salted code identity must be exercised",signatures>100);
    assertSame(TicketControlCodeVisualClassifier.RAW_TICKET,TicketControlCodeVisualClassifier.classify(detail()));
    System.out.println("control JNI low/high parity: "+cases+" probes; "+bounds+" close targets; "+signatures+" signatures; states "+observed);
  }
  private static int[] submit(boolean dark,boolean keyboard,int valueCount,int span) {
    int[] p=new int[96*144];Arrays.fill(p,0xff303030);
    if(dark) {
      paint(p,96,8,keyboard?24:52,88,keyboard?62:86,0xff303030);
      paint(p,96,8,keyboard?40:68,88,keyboard?56:78,0xff2050a0);
      for(int i=0;i<valueCount;i++)p[(63+i/2)*96+32+(i%2)*span]=-1;
    } else {
      paint(p,96,16,keyboard?32:60,80,keyboard?60:90,0xffeeeeee);
      paint(p,96,62,keyboard?48:73,84,keyboard?58:82,0xffffa000);
      for(int i=0;i<valueCount;i++)p[(65+i/2)*96+32+(i%2)*span]=0xff202020;
    }
    return p;
  }
  @Test public void wideEnteredValueRejectsMeasuredPlaceholderAndUnsafeLayouts() {
    int[] empty=submit(false,false,0,0),placeholder=empty.clone(),entered=empty.clone();
    // Synthetic shapes reproduce only the live scalar measurements, never captured pixels:
    // placeholder 18 pixels / 10 columns / span 25 / widest row 9;
    // entered value 18 pixels / 15 columns / span 24 / widest row 8.
    paint(placeholder,96,32,65,41,66,0xff202020);
    paint(placeholder,96,32,66,39,67,0xff202020);
    placeholder[66*96+57]=0xff202020;placeholder[67*96+32]=0xff202020;
    paint(entered,96,32,65,40,66,0xff202020);
    paint(entered,96,40,66,46,67,0xff202020);entered[66*96+56]=0xff202020;
    paint(entered,96,32,67,35,68,0xff202020);
    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(placeholder));
    assertEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.classifySubmitLayout(entered));
    assertEquals("28,63,68,71",TicketControlCodeVisualClassifier.submitInputBounds(entered));
    assertEquals("62,73,84,82",TicketControlCodeVisualClassifier.submitButtonBounds(entered));
    int[] column=empty.clone(),line=empty.clone(),noSubmit=entered.clone();
    paint(column,96,32,64,33,72,0xff202020);
    paint(line,96,28,65,68,66,0xff202020);
    paint(noSubmit,96,62,73,84,82,0xffeeeeee);
    for(int[] p:new int[][]{empty,submit(false,false,4,0),column,line,submit(false,true,0,0),
      noSubmit,null,new int[96*144-1],new int[96*144+1]}) {
      assertNotEquals(TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
        TicketControlCodeVisualClassifier.classifySubmitLayout(p));
    }
    for(int[] p:new int[][]{submit(false,true,0,0),noSubmit,null,new int[96*144-1]}) {
      assertEquals("",TicketControlCodeVisualClassifier.submitInputBounds(p));
      assertEquals("",TicketControlCodeVisualClassifier.submitButtonBounds(p));
    }
  }
  @Test public void popupValueCaretKeyboardAndDetectedBoundsMatch() throws Exception {
    sameFixtureSalt();for(boolean dark:new boolean[]{false,true})for(boolean keyboard:new boolean[]{false,true})for(int pixels:new int[]{0,1,2,3,4,8})for(int span:new int[]{0,1,2,3,19,20,21,25}) {
      int[] p=submit(dark,keyboard,pixels,span);high(p);high(transformed(p,-10,false));high(transformed(p,10,false));
    }
    assertTrue(observed.toString(),observed.containsAll(Set.of("control_popup_static_ready","control_popup_value_ready","control_popup_keyboard_ready")));
    for(int[] p:new int[][]{null,new int[0],new int[7],new int[96*144-1],new int[96*144+1]})high(p);
    for(int[] p:new int[][]{null,new int[0],new int[7],new int[48*72-1],new int[48*72+1]}) {
      same("invalid normal",LegacyTicketControlCodeVisualClassifier.classify(p),TicketControlCodeVisualClassifier.classify(p));
      same("invalid activated",LegacyTicketControlCodeVisualClassifier.classifyForActivatedTicket(p),TicketControlCodeVisualClassifier.classifyForActivatedTicket(p));
      same("invalid close",LegacyTicketControlCodeVisualClassifier.generatedResultCloseBounds(p),TicketControlCodeVisualClassifier.generatedResultCloseBounds(p));
      same("invalid slider",LegacyTicketControlCodeVisualClassifier.registrationSliderBounds(p),TicketControlCodeVisualClassifier.registrationSliderBounds(p));
      same("invalid signature",LegacyTicketControlCodeVisualClassifier.ticketCodeVisualSignature(p),TicketControlCodeVisualClassifier.ticketCodeVisualSignature(p));
      // Old cleanup could throw on malformed input. Native now follows all other
      // public methods and refuses authority with unknown; no valid probe changes.
      assertSame(TicketControlCodeVisualClassifier.UNKNOWN,TicketControlCodeVisualClassifier.classifyForCleanup(p));
    }
    System.out.println("control JNI submit parity: "+cases+" probes; states "+observed);
  }
  @Test public void staticIdentityKeepsDomainPrefixQuantizationAndSamplingOrder() throws Exception {
    sameFixtureSalt();Random random=new Random(482171);
    for(int[] shape:new int[][]{{48,72},{49,73},{71,97},{96,144},{192,288},{384,576}})for(int level:new int[]{0,15,16,17,31,32,33,127,128,129,239,240,241,255}) {
      int w=shape[0],h=shape[1];int[] p=new int[w*h];Arrays.fill(p,0xff000000|level*0x010101);
      for(int i=0;i<40;i++)p[random.nextInt(p.length)]^=random.nextInt(0xffffff);
      same("static opaque identity",LegacyTicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(p,w,h),TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(p,w,h));
      String original=TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(p,w,h);
      paint(p,w,0,0,w,h*34/72,0xff123456);
      assertEquals("rotating code area cannot change static identity",original,TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(p,w,h));cases++;
    }
    for(int[] shape:new int[][]{{0,0},{48,71},{47,72},{-48,72},{48,-72}})same("invalid static",LegacyTicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(new int[0],shape[0],shape[1]),TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(new int[0],shape[0],shape[1]));
    same("null static",LegacyTicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(null,48,72),TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(null,48,72));
    System.out.println("control static signature parity: "+cases+" images");
  }
  @Test public void sparseHighResolutionResultCrossSurvivesWithoutCompactTapAuthority() throws Exception {
    sameFixtureSalt();
    java.lang.reflect.Method compact = LegacyTicketControlCodeVisualClassifier.class.getDeclaredMethod("compactFromSubmitProbe",int[].class);
    compact.setAccessible(true);
    int highOnly=0;
    List<int[]> points=new ArrayList<>();
    points.add(new int[]{0,0});points.add(new int[]{2,0});points.add(new int[]{1,1});
    for(int y=0;y<6;y++)for(int x=0;x<8;x++) {
      final int px=x,py=y;
      if(points.stream().noneMatch(v->v[0]==px&&v[1]==py))points.add(new int[]{x,y});
    }
    for(int count=0;count<=35;count++)for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++) {
      int[] p=doubled(generated(30,32,255));
      // Keep the strip's independent bright-digit proof outside the close zone.
      paint(p,96,40,64,42,66,-1);paint(p,96,44,64,46,66,-1);
      paint(p,96,68,60,82,70,0xff202020);
      for(int i=0;i<count;i++) {
        int[] point=points.get(i);p[(62+dy+point[1])*96+72+dx+point[0]]=-1;
      }
      String old=LegacyTicketControlCodeVisualClassifier.generatedResultCloseBoundsHighResolution(p);
      high(p);
      int[] small=(int[])compact.invoke(null,(Object)p);
      if(!old.isEmpty()&&LegacyTicketControlCodeVisualClassifier.generatedResultCloseBounds(small).isEmpty())highOnly++;
      if(count<3||count>32)assertEquals("bright-count fence", "",old);
    }
    assertTrue("real high-resolution close proof must survive compact loss",highOnly>0);
    System.out.println("sparse high-resolution close JNI parity: "+cases+" probes; "+highOnly+" high-only targets");
  }
}
