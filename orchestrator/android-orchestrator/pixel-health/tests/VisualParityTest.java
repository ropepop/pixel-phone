package lv.jolkins.pixelorchestrator.app.ticket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual JNI compared with the retained Java release, using only synthetic pixels. */
public class VisualParityTest {
  private static int cases;
  private static int recognized;

  private static void sameHostFixtureSalt() throws Exception {
    Field old = LegacyTicketVisualDateGlyphRecognizer.class.getDeclaredField("ANCHOR_SALT");
    Field now = TicketVisualDateGlyphRecognizer.class.getDeclaredField("ANCHOR_SALT");
    old.setAccessible(true); now.setAccessible(true);
    // Both classes are helper-owned host fixtures. Never reads a phone or exports a key.
    System.arraycopy((byte[])now.get(null), 0, (byte[])old.get(null), 0, 32);
  }
  private static void compare(int[] pixels, int width, int height) {
    List<LegacyTicketVisualDateGlyphRecognizer.DateRange> old = LegacyTicketVisualDateGlyphRecognizer.recognize(pixels,width,height);
    List<TicketVisualDateGlyphRecognizer.DateRange> now = TicketVisualDateGlyphRecognizer.recognize(pixels,width,height);
    assertEquals("date count case " + cases,old.size(),now.size());
    for(int i=0;i<old.size();i++) {
      assertEquals(old.get(i).from,now.get(i).from);
      assertEquals(old.get(i).until,now.get(i).until);
      assertEquals(old.get(i).anchor,now.get(i).anchor);
      assertEquals(old.get(i).centerY,now.get(i).centerY);
    }
    cases++; if(!now.isEmpty()) recognized++;
  }
  @Test public void retainedImageFixturesAndNativeDateResultsAgree() throws Exception {
    sameHostFixtureSalt();
    Object fixtures=new TicketIdleRefreshTest();
    for(String name:List.of("homePixels","oldTimeListPixels")) {
      Method fixture=TicketIdleRefreshTest.class.getDeclaredMethod(name);fixture.setAccessible(true);
      int[] pixels=(int[])fixture.invoke(fixtures);
      compare(pixels,pixels.length==192*288?192:384,pixels.length==192*288?288:576);
    }
    Method fixture=TicketIdleRefreshTest.class.getDeclaredMethod("dateFixture",String.class,int.class);fixture.setAccessible(true);
    for(String text:List.of("01.04.2031-30.04.2031","29.02.2024-28.02.2026","29.02.2024-01.03.2026","01.01.0000-01.01.0001","31.04.2031-30.04.2031","01.01.9998-31.12.9999","30.04.2031-01.04.2031","01.04.2031","23.09.2099-22.10.2099")) {
      for(int top:new int[]{0,150,285,308,360,569}) {
        int[] pixels=(int[])fixture.invoke(fixtures,text,top);
        compare(pixels,384,576);
        for(int threshold:new int[]{114,115,116,149,150,151,184,185,186,204,205,206}) {
          int[] gray=pixels.clone();
          for(int i=0;i<gray.length;i++) if(gray[i]!=-1) gray[i]=0xff000000|threshold*0x010101;
          compare(gray,384,576);
        }
      }
    }
    compare(null,384,576);compare(new int[0],0,0);compare(new int[12],3,5);compare(new int[12],-3,4);
    assertTrue("successful recognition must be exercised",recognized>50);
    System.out.println("visual date JNI parity: "+cases+" images; "+recognized+" recognized");
  }
  @Test public void embeddedGlyphMasksAndScaleRemainCompatible() throws Exception {
    sameHostFixtureSalt();
    Field field=LegacyTicketVisualDateGlyphRecognizer.class.getDeclaredField("RUNTIME_DIGITS");field.setAccessible(true);
    @SuppressWarnings("unchecked") List<LegacyTicketVisualDateGlyphRecognizer.RenderedGlyphTemplate> templates=(List<LegacyTicketVisualDateGlyphRecognizer.RenderedGlyphTemplate>)field.get(null);
    List<LegacyTicketVisualDateGlyphRecognizer.RenderedGlyphTemplate> set=new ArrayList<>(templates.subList(0,10));
    int successful=0;
    for(int variant=0;variant<templates.size();variant++) {
      LegacyTicketVisualDateGlyphRecognizer.RenderedGlyphTemplate chosen=templates.get(variant);set.set(chosen.value-'0',chosen);
      for(int scale:new int[]{1,2}) for(boolean inverse:new boolean[]{false,true}) {
        int w=576,h=112;int[] pixels=new int[w*h];Arrays.fill(pixels,inverse?0xff000000:0xffffffff);
        String text="01.06.2026-30.06.2026";
        for(int i=0;i<text.length();i++) {
          char digit=text.charAt(i);if(digit<'0'||digit>'9')continue;
          var glyph=set.get(digit-'0');
          for(int y=0;y<glyph.height*scale;y++) for(int x=0;x<glyph.width*scale;x++) if(glyph.pixels[(y/scale)*glyph.width+x/scale]) pixels[(y+20)*w+12+i*14*scale+x]=inverse?0xffffffff:0xff000000;
        }
        compare(pixels,w,h);if(!TicketVisualDateGlyphRecognizer.recognize(pixels,w,h).isEmpty())successful++;
        Random noise=new Random(variant*4L+scale);
        for(int i=0;i<20;i++)pixels[noise.nextInt(pixels.length)]^=0x00ffffff;
        compare(pixels,w,h);
      }
    }
    assertTrue("rendered-font success path",successful>20);
  }
  static int[] scaled(int[] pixels,int width,int height,int scale) {
    int[] result=new int[width*height*scale*scale];
    for(int y=0;y<height*scale;y++)for(int x=0;x<width*scale;x++)result[y*width*scale+x]=pixels[(y/scale)*width+x/scale];
    return result;
  }
  static void paint(int[] p,int width,int left,int top,int right,int bottom,int color) {
    for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)p[y*width+x]=color;
  }
  static int[] detailPixels(boolean slider,boolean close) {
    int[] p=new int[192*288];Arrays.fill(p,0xffb0b0b0);
    paint(p,192,0,0,192,30,0xff30353a);
    paint(p,192,4,32,188,56,0xffbf4020);
    for(int y=56;y<136;y++)for(int x=32;x<160;x++)p[y*192+x]=((x/4+y/4)%2==0)?-1:0xff202020;
    paint(p,192,28,144,164,160,-1);
    for(int x=36;x<156;x+=12)paint(p,192,x,148,x+4,152,0xff202020);
    if(slider) {
      paint(p,192,24,180,176,204,0xffffa000);
      paint(p,192,18,180,40,204,0xff202020);
    }
    if(close) for(int i=0;i<9;i++) {
      p[(9+i)*192+169+i]=-1;p[(9+i)*192+177-i]=-1;
    }
    return p;
  }
  private static String normalizeLegacyEpoch(String wire) {
    return wire.replace(LegacyTicketControlCodeVisualClassifier.ticketCodeVisualSignatureEpoch(),
      TicketControlCodeVisualClassifier.ticketCodeVisualSignatureEpoch());
  }
  private static void compareAction(int[] p,Set<String> observed) {
    var old=LegacyTicketVisualActionClassifier.classify(p);
    var now=TicketVisualActionClassifier.classify(p);
    assertEquals("full action state",normalizeLegacyEpoch(old.wire()),now.wire());
    assertEquals("detail-only action",normalizeLegacyEpoch(LegacyTicketVisualActionClassifier.classifyCurrent(p).wire()),
      TicketVisualActionClassifier.classifyCurrent(p).wire());
    assertEquals("route authority",LegacyTicketVisualActionClassifier.selectedBottomNavigationTab(p),
      TicketVisualActionClassifier.selectedBottomNavigationTab(p));
    observed.add(now.state);
  }
  @Test public void actionAuthorityBoundsAndOpaqueIdentityMatch() throws Exception {
    sameHostFixtureSalt();
    Field oldSalt=LegacyTicketControlCodeVisualClassifier.class.getDeclaredField("VISUAL_SIGNATURE_SALT");
    Field nowSalt=TicketControlCodeVisualClassifier.class.getDeclaredField("VISUAL_SIGNATURE_SALT");
    oldSalt.setAccessible(true);nowSalt.setAccessible(true);
    System.arraycopy((byte[])nowSalt.get(null),0,(byte[])oldSalt.get(null),0,32);
    Set<String> observed=new TreeSet<>();List<int[]> images=new ArrayList<>();
    Method home=TicketIdleRefreshTest.class.getDeclaredMethod("homePixels");home.setAccessible(true);
    int[] base=(int[])home.invoke(new TicketIdleRefreshTest());images.add(base);
    for(int selected=0;selected<4;selected++) {
      int[] p=base.clone();
      for(int y=262;y<283;y++)for(int x=0;x<192;x++)if(p[y*192+x]==0xffffa000||p[y*192+x]==-1) {
        int region=x<40?0:x<90?1:x<145?2:3;p[y*192+x]=region==selected?0xffffa000:-1;
      }
      images.add(p);
      for(int missing=0;missing<4;missing++) {int[] covered=p.clone();paint(covered,192,missing*48,258,(missing+1)*48,284,0xff30353a);images.add(covered);}
    }
    int[] popup=base.clone();paint(popup,192,32,120,160,180,-1);paint(popup,192,124,156,168,176,0xffffa000);images.add(popup);
    int[] login=base.clone();paint(login,192,32,100,160,200,-1);paint(login,192,32,204,160,224,0xff0055ff);images.add(login);
    for(boolean slider:new boolean[]{false,true})for(boolean close:new boolean[]{false,true})images.add(detailPixels(slider,close));
    int[] withControl=detailPixels(false,true);paint(withControl,192,8,8,74,18,0xffffa000);images.add(withControl);
    int[] duplicateClose=detailPixels(false,true);
    for(int i=0;i<7;i++) {duplicateClose[(17+i)*192+158+i]=-1;duplicateClose[(17+i)*192+164-i]=-1;}
    images.add(duplicateClose);
    for(int color:new int[]{0,0xff000000,-1,0xffffa000,0xff30353a}) {int[] p=new int[192*288];Arrays.fill(p,color);images.add(p);}
    Random random=new Random(84632);
    for(int[] p:images) {
      compareAction(p,observed);compareAction(scaled(p,192,288,2),observed);
      int[] noise=p.clone();for(int i=0;i<40;i++)noise[random.nextInt(noise.length)]^=0x00ffffff;
      compareAction(noise,observed);
    }
    Method oldList=TicketIdleRefreshTest.class.getDeclaredMethod("oldTimeListPixels");oldList.setAccessible(true);
    compareAction((int[])oldList.invoke(new TicketIdleRefreshTest()),observed);
    Method draw=TicketIdleRefreshTest.class.getDeclaredMethod("drawDate",int[].class,String.class,int.class,int.class,int.class);draw.setAccessible(true);
    for(String second:List.of("01.04.2031-30.04.2031","01.04.2099-30.04.2099","01.04.1970-30.04.1970","")) {
      int[] list=(int[])oldList.invoke(new TicketIdleRefreshTest());
      paint(list,384,16,108,368,310,-1);paint(list,384,24,250,360,288,0xffffa000);
      draw.invoke(new TicketIdleRefreshTest(),list,"01.04.2099-30.04.2099",205,24,0xff101010);
      if(!second.isEmpty()) {
        paint(list,384,16,312,368,500,-1);paint(list,384,24,430,360,470,0xffffa000);
        draw.invoke(new TicketIdleRefreshTest(),list,second,380,24,0xff101010);
      }
      compareAction(list,observed);
    }
    compareAction(null,observed);compareAction(new int[0],observed);compareAction(new int[73],observed);
    assertTrue(observed.toString(),observed.containsAll(Set.of("unknown","vivi_home","vivi_profile","vivi_other_tab","login_required","blocked","ticket_list","activated_detail","unactivated_detail")));
    System.out.println("visual action JNI parity: states "+observed);
  }

  @Test public void nativeSamplePhasesDistinguishLabelAliasFromRealClose() throws Exception {
    sameHostFixtureSalt();
    Method fixture=TicketIdleRefreshTest.class.getDeclaredMethod("oldTimeListPixels");fixture.setAccessible(true);
    Set<String> observed=new TreeSet<>();
    for(int mode=0;mode<3;mode++) {
      int[] p=(int[])fixture.invoke(new TicketIdleRefreshTest());
      paint(p,384,200,36,368,68,0xff30353a);
      paint(p,384,224,46,292,50,-1);
      int cx=mode==2?178:160,cy=24;
      for(int step=-3;step<=3;step++)for(int sign:new int[]{-1,1}) {
        int x=cx+step,y=cy+step*sign;
        if(mode==0) {
          int ox=step<0?0:1,oy=step*sign<0?0:1;
          p[(2*y+oy)*384+2*x+ox]=-1;
        } else paint(p,384,2*x,2*y,2*x+2,2*y+2,-1);
      }
      if(mode==0)paint(p,384,cx*2,cy*2,cx*2+2,cy*2+2,-1);
      var baseline=LegacyTicketVisualActionClassifier.classify(p);
      if(mode==0) {
        assertNotNull("alias must actually produce a close-shaped reduced header",LegacyTicketVisualActionClassifier.classifyCurrent(p).backBounds);
        assertNotNull("absence of every native X phase permits the opposite tab",baseline.ticketsTabBounds);
      } else assertNull("a real native X never grants a tab target",baseline.ticketsTabBounds);
      compareAction(p,observed);
    }
  }

}
