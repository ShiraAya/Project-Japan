package com.sora.pjr.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Current user coordinates, dense adjacent terrain checks, and removed-coast probes. */
public final class FeedbackCoastValidation {
 public static void main(String[] args)throws Exception {
  WorldEngine e=WorldEngine.load();PrecisionTerrain p=new PrecisionTerrain();long pairs=0;int max=0,full=0,blend=0;List<String> areas=new ArrayList<>();
  for(int[] c:new int[][]{{-214483,60810},{-214979,60288},{-215695,59838},{-215587,59738}}){int m=0;
   for(int z=c[1]-192;z<=c[1]+192;z++)for(int x=c[0]-192;x<=c[0]+192;x++){
    var g=e.geometry(x,z);if(g.wet())continue;
    for(int[] d:new int[][]{{1,0},{0,1}}){var n=e.geometry(x+d[0],z+d[1]);if(n.wet())continue;m=Math.max(m,Math.abs(g.groundY()-n.groundY()));pairs++;}
   }
   max=Math.max(max,m);areas.add(c[0]+","+c[1]+":"+m);
  }
  if(max>2)throw new AssertionError("Screenshot terrain still has abrupt steps: "+areas);
  for(String row:Files.readAllLines(Path.of(args[0]))){if(row.startsWith("x,"))continue;String[] f=row.split(",");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);var coast=p.coast(x,z);
   if(coast==null||coast.weight()<1){blend++;continue;}
   if(e.hydrology().terrain().sample(x,z).land())throw new AssertionError("Pruned port still land "+row);full++;
  }
  var okayama=e.geometry(-257820,62406);if(okayama.kind()!=Hydrology.Kind.OCEAN)throw new AssertionError("Okayama sea regression");
  System.out.println("FEEDBACK COAST PASS screenshotAdjacentPairs="+pairs+" maxStep="+max+" areas="+areas+" fullAuthorityRemovedProbes="+full+" blendBandProbes="+blend+" okayama=OCEAN");
 }
}
