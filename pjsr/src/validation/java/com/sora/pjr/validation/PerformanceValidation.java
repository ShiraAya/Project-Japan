package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Same fixed workload for baseline and optimized runtime; checks exact generated columns. */
public final class PerformanceValidation {
  public static void main(String[] args) throws Exception {
    long start=System.nanoTime();
    WorldEngine engine=WorldEngine.load();
    System.out.printf(Locale.ROOT,"engineLoadSeconds=%.6f%n",(System.nanoTime()-start)/1e9);
    long profileNodes=0,points=0,segments=0;
    for(River r:engine.hydrology().data().rivers()) {
      profileNodes+=r.profileSize();segments+=r.segments().size();
    }
    System.out.println("profileNodes="+profileNodes+"; segments="+segments);
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    int[][] centres={{0,0},{(int)Geo.x(139.79),(int)Geo.z(35.68)},
      {(int)Geo.x(135.50),(int)Geo.z(34.69)},{(int)Geo.x(135.90),(int)Geo.z(35.15)},
      {(int)Geo.x(138.73),(int)Geo.z(35.36)},{(int)Geo.x(141.35),(int)Geo.z(43.06)}};
    int chunks=0;
    long total=System.nanoTime();
    for(int area=0;area<centres.length;area++) {
      long at=System.nanoTime();
      for(int cz=-2;cz<=2;cz++)for(int cx=-2;cx<=2;cx++) {
        int ox=(Math.floorDiv(centres[area][0],16)+cx)*16;
        int oz=(Math.floorDiv(centres[area][1],16)+cz)*16;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
          Column c=engine.column(ox+x,oz+z);Geometry g=c.geometry();
          digest.update((g.toString()+"/"+c.surfaceHeight()+"/").getBytes(StandardCharsets.UTF_8));
          for(int y=Geo.MIN_Y;y<c.surfaceHeight()+1;y++)digest.update((byte)c.at(y).ordinal());
        }
        chunks++;
      }
      System.out.printf(Locale.ROOT,"area%dSeconds=%.6f%n",area,(System.nanoTime()-at)/1e9);
    }
    System.out.printf(Locale.ROOT,"columnGenerationSeconds=%.6f%n",(System.nanoTime()-total)/1e9);
    System.out.println("chunks="+chunks+"; columns="+(chunks*256));
    System.out.println("columnSHA256="+HexFormat.of().formatHex(digest.digest()));
  }
}
