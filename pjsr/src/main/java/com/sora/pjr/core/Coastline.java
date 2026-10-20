package com.sora.pjr.core;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Indexed contour of the exterior-connected ocean mask. No inland lake shore is an ocean coast. */
final class Coastline {
  private record Edge(double ax, double az, double bx, double bz) {
    Bounds bounds() { return new Bounds(Math.min(ax,bx),Math.min(az,bz),Math.max(ax,bx),Math.max(az,bz)); }
    double distance(double x, double z) {
      double dx=bx-ax, dz=bz-az;
      double t=Geo.clamp(((x-ax)*dx+(z-az)*dz)/Math.max(1e-12,dx*dx+dz*dz),0,1);
      return Math.hypot(x-ax-t*dx,z-az-t*dz);
    }
  }
  static final double REACH=1024;
  private final SpatialIndex<Edge> index;
  Coastline() {
    List<Edge> edges=new ArrayList<>();
    try (var raw=Coastline.class.getResourceAsStream("/assets/pjr/terrain/coast.pjrc.gz")) {
      if(raw==null) throw new IOException("Missing ocean contour");
      try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
        if(in.readInt()!=0x504a5243) throw new IOException("Invalid coast format");
        int n=in.readInt();
        if(n<1 || n>1000000) throw new IOException("Invalid coast count");
        for(int i=0;i<n;i++) edges.add(new Edge(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()));
        if(in.read()!=-1) throw new IOException("Trailing coast data");
      }
    } catch(IOException e) { throw new IllegalStateException("Cannot load coastline",e); }
    index=new SpatialIndex<>(edges,512,e->e.bounds().expand(REACH));
  }
  double distance(int x,int z) {
    double best=REACH;
    for(Edge e:index.at(x,z)) if(e.bounds().distanceSquared(x,z)<best*best) best=Math.min(best,e.distance(x,z));
    return best;
  }
}
