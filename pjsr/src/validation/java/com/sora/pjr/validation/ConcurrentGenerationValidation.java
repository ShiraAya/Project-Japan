package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Work-stealing style queries must produce the same columns as single-threaded generation. */
public final class ConcurrentGenerationValidation {
  private static List<Column> chunk(WorldEngine e,int[] p) {
    List<Column> result=new ArrayList<>(256);
    for(int z=0;z<16;z++)for(int x=0;x<16;x++)result.add(e.column(p[0]+x,p[1]+z));
    e.releaseThreadCache();return result;
  }
  public static void main(String[] args)throws Exception {
    WorldEngine engine=WorldEngine.load();List<int[]> chunks=new ArrayList<>();
    for(double[] p:new double[][]{{139.767125,35.681236},{139.79,35.68},{139.90,35.36},
      {135.50,34.69},{135.90,35.15},{138.73,35.36},{141.35,43.06},{130.40,33.59}}) {
      int ox=Math.floorDiv((int)Geo.x(p[0]),16)*16,oz=Math.floorDiv((int)Geo.z(p[1]),16)*16;
      for(int z=-3;z<=3;z++)for(int x=-3;x<=3;x++)chunks.add(new int[]{ox+16*x,oz+16*z});
    }
    List<List<Column>> expected=new ArrayList<>();
    for(var p:chunks)expected.add(chunk(engine,p));
    ExecutorService pool=Executors.newFixedThreadPool(8);
    try {
      List<Future<List<Column>>> futures=new ArrayList<>();
      for(var p:chunks)futures.add(pool.submit(()->chunk(engine,p)));
      for(int i=0;i<futures.size();i++)if(!futures.get(i).get().equals(expected.get(i)))throw new AssertionError("Concurrent column mismatch "+i);
    } finally {pool.shutdownNow();}
    System.out.println("PJR concurrent generation validation PASS\nthreads=8\nchunks="+chunks.size()+"\ncolumns="+(chunks.size()*256));
  }
}
