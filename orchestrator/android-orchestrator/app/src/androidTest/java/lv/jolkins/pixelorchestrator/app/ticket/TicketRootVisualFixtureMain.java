package lv.jolkins.pixelorchestrator.app.ticket;

import android.os.Debug;
import java.util.Arrays;
import java.util.List;

/** Synthetic pixels only. No capture, network, input, UI, or persistent fixture is created. */
public final class TicketRootVisualFixtureMain {
  private static void require(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  private static void paint(int[] p,int width,int left,int top,int right,int bottom,int color) {
    for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)p[y*width+x]=color;
  }
  private static int[] detail() {
    int[] p=new int[192*288];Arrays.fill(p,0xffb0b0b0);
    paint(p,192,0,0,192,30,0xff30353a);paint(p,192,4,32,188,56,0xffbf4020);
    for(int y=56;y<136;y++)for(int x=32;x<160;x++)p[y*192+x]=((x/4+y/4)%2==0)?-1:0xff202020;
    paint(p,192,28,144,164,160,-1);
    for(int x=36;x<156;x+=12)paint(p,192,x,148,x+4,152,0xff202020);
    for(int i=0;i<9;i++){p[(9+i)*192+169+i]=-1;p[(9+i)*192+177-i]=-1;}
    return p;
  }
  private static int[] dates() {
    // Independent public seven-pixel numeral fixture; never uses captured ticket content.
    String[] glyphs={
      ".###./##.##/##.##/##.##/##.##/##.##/.###.",
      "..##./.###./..##./..##./..##./..##./.####",
      ".###./##.##/...##/..##./.##../##.../#####",
      "####./...##/...##/.###./...##/...##/####.",
      "...##/..###/.#.##/##.##/#####/...##/...##"
    };
    String text="01.04.2031-30.04.2031";
    int[] p=new int[576*112];Arrays.fill(p,-1);
    for(int i=0;i<text.length();i++) {
      char c=text.charAt(i);if(c<'0'||c>'4')continue;
      String[] rows=glyphs[c-'0'].split("/");
      for(int y=0;y<7;y++)for(int x=0;x<5;x++)if(rows[y].charAt(x)=='#')p[(36+y)*576+12+i*12+x]=0xff000000;
    }
    return p;
  }
  public static void main(String[] args) {
    int[] detail=detail(),dates=dates();
    long coldStart=System.nanoTime();
    TicketVisualActionClassifier.Result first=TicketVisualActionClassifier.classify(detail);
    require(first.state.equals("activated_detail"),"detail state");
    require(first.backBounds!=null&&first.backBounds.wire().equals("159,0,187,26"),"close bounds");
    require(first.currentAnchor.matches("d_[0-9a-f]{28}"),"detail identity shape");
    List<TicketVisualDateGlyphRecognizer.DateRange> range=TicketVisualDateGlyphRecognizer.recognize(dates,576,112);
    require(range.size()==1,"date range count");
    require(range.get(0).from.toString().equals("2031-04-01")&&range.get(0).until.toString().equals("2031-04-30"),"date values");
    String anchor=range.get(0).anchor;
    require(anchor.matches("[0-9a-f]{24}")&&anchor.equals(TicketVisualActionClassifier.detailCardAnchor(dates)),"date anchor");
    long coldUs=(System.nanoTime()-coldStart)/1000;
    int[] compact=new int[48*72];for(int y=0;y<72;y++)for(int x=0;x<48;x++)compact[y*48+x]=detail[y*4*192+x*4];
    require(TicketControlCodeVisualClassifier.classify(compact)==TicketControlCodeVisualClassifier.RAW_TICKET,"control state identity");
    String code=TicketControlCodeVisualClassifier.ticketCodeVisualSignature(compact);
    require(code.matches("[0-9a-f]{24}")&&code.equals(TicketControlCodeVisualClassifier.ticketCodeVisualSignature(compact.clone())),"code identity");
    require(TicketControlCodeVisualClassifier.classifyForCleanup(null)==TicketControlCodeVisualClassifier.UNKNOWN,"malformed cleanup");
    require(TicketVisualDateGlyphRecognizer.recognize(null,384,576).isEmpty(),"missing date input");
    require(TicketVisualActionClassifier.classify(null).state.equals("unknown"),"missing action input");
    long cpu=Debug.threadCpuTimeNanos();long[] samples=new long[32];
    for(int i=0;i<samples.length;i++) {
      long start=System.nanoTime();
      require(TicketVisualActionClassifier.classify(detail).wire().equals(first.wire()),"stable action");
      require(TicketVisualActionClassifier.classifyCurrent(detail).wire().equals(first.wire()),"detail-only parity");
      require(TicketVisualActionClassifier.selectedBottomNavigationTab(detail).isEmpty(),"no navigation authority");
      require(TicketVisualActionClassifier.detailCardAnchor(dates).equals(anchor),"stable date");
      samples[i]=(System.nanoTime()-start)/1000;
    }
    cpu=(Debug.threadCpuTimeNanos()-cpu)/1000;Arrays.sort(samples);
    System.out.println("NATIVE_VISUAL_OK date="+anchor+" detail="+first.currentAnchor+" cold_us="+coldUs+" p95_us="+samples[30]+" cpu_us="+cpu+" native_heap_bytes="+Debug.getNativeHeapAllocatedSize());
  }
}
