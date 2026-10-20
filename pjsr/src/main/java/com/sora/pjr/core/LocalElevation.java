package com.sora.pjr.core;

import java.io.*;
import java.util.zip.GZIPInputStream;

/** Immutable local GSI relief; smooth edge blending and explicit missing-height fallback. */
final class LocalElevation {
  private final double x0, z0, step, blend;
  private final int width,height;
  private final float[] metres;

  LocalElevation() {
    try (InputStream resource = LocalElevation.class.getResourceAsStream(
            "/assets/pjr/terrain/sameura-dem.pjrd.gz")) {
      if (resource == null) throw new IOException("Missing local terrain patch");
      try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(resource)))) {
        if (in.readInt() != 0x504a5244) throw new IOException("Bad DEM header");
        int version=in.readInt();
        if(version<1 || version>3)throw new IOException("Unsupported local DEM");
        double sourceScale=version>=2?in.readDouble():0.375;
        if(!(sourceScale>0 && sourceScale<=1))throw new IOException("Invalid source projection");
        double ratio=Geo.SCALE/sourceScale;
        x0=in.readInt()*ratio; z0=in.readInt()*ratio; step=in.readInt()*ratio;
        width=in.readInt(); height=in.readInt(); blend=in.readInt()*ratio;
        if (step<1 || width<2 || height<2 || (long)width*height>16_000_000 || blend<1)
          throw new IOException("Invalid DEM dimensions");
        metres=new float[width*height];
        if(version==3) {
          float quantum=in.readFloat();int codec=in.readUnsignedByte();if(quantum!=.5f)throw new IOException("Invalid local DEM quantum");
          short[] q=CompactDemCodec.decodeSigned16(in,width,height,codec);
          for(int i=0;i<q.length;i++)metres[i]=q[i]==Short.MIN_VALUE?Float.NaN:q[i]*quantum;
        } else for (int i=0;i<metres.length;i++) metres[i]=in.readFloat();
        if (in.read()!=-1) throw new IOException("Trailing DEM data");
      }
    } catch (IOException e) { throw new IllegalStateException("Cannot load local terrain",e); }
  }

  double sample(int x,int z,double fallback) {
    double px=(x-x0)/(double)step, pz=(z-z0)/(double)step;
    if(px<0 || pz<0 || px>=width-1 || pz>=height-1) return fallback;
    int ix=(int)px, iz=(int)pz;
    double tx=px-ix,tz=pz-iz,sum=0,weight=0;
    for(int k=0;k<4;k++) {
      float value=metres[(iz+((k&2)==0?0:1))*width+ix+(k&1)];
      if(!Float.isFinite(value)) continue;
      double w=((k&1)==0?1-tx:tx)*((k&2)==0?1-tz:tz);
      sum+=w*value;weight+=w;
    }
    if(weight<.001) return fallback;
    double edge=step*Math.min(Math.min(px,pz),Math.min(width-1-px,height-1-pz));
    return fallback+(sum/weight-fallback)*Geo.smooth(0,blend,edge);
  }
}
