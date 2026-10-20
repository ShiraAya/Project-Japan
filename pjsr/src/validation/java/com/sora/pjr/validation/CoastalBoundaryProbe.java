package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;

/** Same perimeter inputs evaluated against separately loaded old and new release classes. */
public final class CoastalBoundaryProbe {
  public static void main(String[] args)throws Exception {
    Terrain t=new RasterTerrain();StringBuilder out=new StringBuilder("region,x,z,land,height\n");
    double[][] regions={{139.63,35.49,139.96,35.72,4},{146.70,44.30,149.15,45.75,32},{145.78,43.30,146.98,44.03,16}};
    for(int r=0;r<regions.length;r++) {
      double[] b=regions[r];int step=(int)b[4];
      int x0=(int)Math.floor(Geo.x(b[0])/step)*step,x1=(int)Math.ceil(Geo.x(b[2])/step)*step;
      int z0=(int)Math.floor(Geo.z(b[3])/step)*step,z1=(int)Math.ceil(Geo.z(b[1])/step)*step;
      for(int z=z0;z<=z1;z+=16)for(int x:new int[]{x0,x1})append(out,t,r,x,z);
      for(int x=x0;x<=x1;x+=16)for(int z:new int[]{z0,z1})append(out,t,r,x,z);
    }
    Files.writeString(Path.of(args[0]),out);
  }
  private static void append(StringBuilder out,Terrain t,int region,int x,int z) {
    var s=t.sample(x,z);out.append(region+","+x+","+z+","+s.land()+","+s.ground()+"\n");
  }
}
