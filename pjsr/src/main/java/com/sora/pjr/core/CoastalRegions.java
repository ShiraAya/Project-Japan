package com.sora.pjr.core;

import java.io.*;
import java.util.zip.GZIPInputStream;

/** Local physical coastlines. Does not read administrative or disputed borders. */
final class CoastalRegions {
  private final Region[] regions = {
      // Island snapshots retain their physical coordinates. Current urban coasts use PrecisionTerrain.
      new Region("iturup"), new Region("shikotan-habomai")
  };

  Region at(int x, int z) {
    for (Region region : regions) if (region.contains(x,z) && region.blend(x,z)>0) return region;
    return null;
  }

  Region beneath(Region selected,int x,int z) {
    boolean passed=false;
    for(Region region:regions) {
      if(passed && region.contains(x,z) && region.blend(x,z)>0)return region;
      if(region==selected)passed=true;
    }
    return null;
  }

  static final class Region {
    private final double x0,z0,step,blend,distanceScale;
    private final int width,height;
    private final short[] signedDistance;
    private final short[] waterDistance;
    private final int version;
    private final float[] metres;
    private final byte[] kinds;
    private final boolean outerCoast;

    Region(String name) {
      outerCoast=name.equals("tokyo-bay-outer");
      try (InputStream resource = CoastalRegions.class.getResourceAsStream(
          "/assets/pjr/terrain/"+name+".pjrt.gz")) {
        if (resource == null) throw new IOException("Missing coastal region: "+name);
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(resource)))) {
          if (in.readInt()!=0x504a5247) throw new IOException("Bad coastal grid header");
          int format=in.readInt();
          if(format<1||format>4)throw new IOException("Unsupported coastal format");
          version=format>=3?in.readInt():format;
          if(version!=1 && version!=2) throw new IOException("Unsupported coastal grid version");
          double sourceScale=format>=3?in.readDouble():0.375;
          if(!(sourceScale>0 && sourceScale<=1))throw new IOException("Invalid source projection");
          distanceScale=Geo.SCALE/sourceScale;
          x0=in.readInt()*distanceScale;z0=in.readInt()*distanceScale;step=in.readInt()*distanceScale;width=in.readInt();height=in.readInt();blend=in.readInt()*distanceScale;
          if (step<1 || step>128 || width<2 || height<2 || (long)width*height>16_000_000 || blend<1)
            throw new IOException("Invalid coastal grid dimensions");
          if(format==4) {
            signedDistance=CompactDemCodec.decodeSigned16(in,width,height,1);
            metres=CompactDemCodec.decodeFloatsXor(in,width,height);
          } else {
            signedDistance=new short[width*height];metres=new float[width*height];
            for(int i=0;i<signedDistance.length;i++)signedDistance[i]=in.readShort();
            for(int i=0;i<metres.length;i++)metres[i]=in.readFloat();
          }
          kinds=in.readNBytes(width*height);
          if(kinds.length!=width*height) throw new IOException("Truncated coastal water kinds");
          for(byte kind:kinds) if(kind<0 || kind>2) throw new IOException("Invalid coastal water kind");
          waterDistance=version==2?(format==4?CompactDemCodec.decodeSigned16(in,width,height,1):new short[width*height]):signedDistance;
          if(version==2&&format!=4)for(int i=0;i<waterDistance.length;i++)waterDistance[i]=in.readShort();
          if(in.read()!=-1) throw new IOException("Trailing coastal grid bytes");
        }
      } catch(IOException e) { throw new IllegalStateException("Cannot load coastal terrain",e); }
    }

    boolean contains(int x,int z) {
      return x>=x0 && z>=z0 && x<x0+(width-1)*step && z<z0+(height-1)*step;
    }
    double blend(int x,int z) {
      double edge=Math.min(Math.min(x-x0,z-z0),Math.min(x0+(width-1)*step-x,z0+(height-1)*step-z));
      return Geo.smooth(0,blend,edge);
    }
    double distance(int x,int z,double baseline) {
      double sum=interpolate(signedDistance,x,z);
      // Fading a thin marine passage into coarse dry land can isolate its far
      // end. V2 keeps surveyed water topology; only elevations and W05 widths blend.
      if(version==2 && !outerCoast && blend(x,z)>0) return sum;
      return baseline+(sum-baseline)*blend(x,z);
    }
    private double interpolate(short[] field,int x,int z) {
      double px=(x-x0)/(double)step,pz=(z-z0)/(double)step;
      int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz,sum=0;
      for(int k=0;k<4;k++) {
        int i=(iz+((k&2)==0?0:1))*width+ix+(k&1);
        double w=((k&1)==0?1-tx:tx)*((k&2)==0?1-tz:tz);
        sum+=w*field[i]/8.0*distanceScale;
      }
      return sum;
    }
    double channelAuthority(int x,int z) { return version==2 && !outerCoast?blend(x,z):0; }
    double waterDistance(int x,int z) { return interpolate(waterDistance,x,z); }
    boolean tidal(int x,int z) {
      // Channel topology is not faded into the coarse sea mask at frame edges.
      if(version==1 && blend(x,z)<.5 || waterDistance(x,z)>=0) return false;
      double px=(x-x0)/(double)step,pz=(z-z0)/(double)step;
      int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz,water=0,channel=0;
      for(int k=0;k<4;k++) {
        byte kind=kinds[(iz+((k&2)==0?0:1))*width+ix+(k&1)];
        double w=((k&1)==0?1-tx:tx)*((k&2)==0?1-tz:tz);
        if(kind>0) water+=w;
        if(kind==2) channel+=w;
      }
      return water>0 && channel/water>=.5;
    }
    boolean elevationSupported(int x,int z) {
      double px=(x-x0)/(double)step,pz=(z-z0)/(double)step;
      int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz,weight=0;
      for(int k=0;k<4;k++) {
        float value=metres[(iz+((k&2)==0?0:1))*width+ix+(k&1)];
        if(Float.isFinite(value)) weight+=((k&1)==0?1-tx:tx)*((k&2)==0?1-tz:tz);
      }
      return weight>=.001;
    }
    double elevation(int x,int z,double baseline) {
      double px=(x-x0)/(double)step,pz=(z-z0)/(double)step;
      int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz,sum=0,weight=0;
      for(int k=0;k<4;k++) {
        float value=metres[(iz+((k&2)==0?0:1))*width+ix+(k&1)];
        if(!Float.isFinite(value)) continue;
        double w=((k&1)==0?1-tx:tx)*((k&2)==0?1-tz:tz);
        sum+=w*value;weight+=w;
      }
      if(weight<.001) return baseline;
      return baseline+(sum/weight-baseline)*blend(x,z);
    }
  }
}
