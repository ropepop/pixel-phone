package lv.jolkins.pixelorchestrator.app.ticket;

import java.lang.reflect.Method;
import java.util.*;

/** Same complete host call sequence, separate JVM per engine; no device or capture side effects. */
public class VisualMeasurements {
  private static int consume;
  public static void main(String[] args) throws Exception {
    boolean rust=args[0].equals("Rust");
    Object source=new TicketIdleRefreshTest();
    Method home=TicketIdleRefreshTest.class.getDeclaredMethod("homePixels");home.setAccessible(true);
    Method list=TicketIdleRefreshTest.class.getDeclaredMethod("oldTimeListPixels");list.setAccessible(true);
    Method date=TicketIdleRefreshTest.class.getDeclaredMethod("dateFixture",String.class,int.class);date.setAccessible(true);
    int[][] fixtures={(int[])home.invoke(source),(int[])list.invoke(source),VisualParityTest.detailPixels(false,true),VisualParityTest.detailPixels(true,true)};
    int[] datePixels=(int[])date.invoke(source,"01.04.2099-30.04.2099",308);
    long start=System.nanoTime();run(rust,fixtures[0],datePixels);double cold=(System.nanoTime()-start)/1e6;
    for(int i=0;i<24;i++)run(rust,fixtures[i%fixtures.length],datePixels);
    Object os=Class.forName("java.lang.management.ManagementFactory").getMethod("getOperatingSystemMXBean").invoke(null);
    Method processCpu=Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime");
    long cpu=(Long)processCpu.invoke(os);double[] samples=new double[120];
    for(int i=0;i<samples.length;i++) {start=System.nanoTime();run(rust,fixtures[i%fixtures.length],datePixels);samples[i]=(System.nanoTime()-start)/1e6;}
    cpu=(Long)processCpu.invoke(os)-cpu;Arrays.sort(samples);
    String rss="unavailable";
    Process process=new ProcessBuilder("ps","-o","rss=","-p",Class.forName("java.lang.ProcessHandle").getMethod("pid").invoke(Class.forName("java.lang.ProcessHandle").getMethod("current").invoke(null)).toString()).start();
    String output=new String(process.getInputStream().readAllBytes()).trim();
    if(process.waitFor()==0&&output.matches("[0-9]+"))rss=output;
    System.out.printf(Locale.ROOT,"visual_measurement engine=%s calls=%d cold_ms=%.3f median_ms=%.3f p95_ms=%.3f p99_ms=%.3f cpu_ms=%.3f rss_kib=%s consumed=%d%n",args[0],samples.length,cold,samples[59],samples[113],samples[118],cpu/1e6,rss,consume);
  }
  private static void run(boolean rust,int[] pixels,int[] dates) {
    if(rust) {
      consume+=TicketVisualActionClassifier.classify(pixels).wire().length();
      consume+=TicketVisualActionClassifier.selectedBottomNavigationTab(pixels).length();
      consume+=TicketVisualDateGlyphRecognizer.recognize(dates,384,576).size();
    } else {
      consume+=LegacyTicketVisualActionClassifier.classify(pixels).wire().length();
      consume+=LegacyTicketVisualActionClassifier.selectedBottomNavigationTab(pixels).length();
      consume+=LegacyTicketVisualDateGlyphRecognizer.recognize(dates,384,576).size();
    }
  }
}
