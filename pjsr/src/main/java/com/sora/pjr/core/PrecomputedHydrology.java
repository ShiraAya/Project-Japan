package com.sora.pjr.core;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact release-time solver output. Seed-independent; never downloaded or written by the game. */
final class PrecomputedHydrology {
  static final String RESOURCE="/assets/pjr/world/hydraulic-startup.bin.gz";
  static final String[] INPUTS={"geography.pjrg.gz","lake-references.properties","river-shapes.properties"};
  record LakeResult(int level,int depth,double area,double bank,int x,int z) {
    Lake restore(RawGeography.Waterbody raw) {
      return new Lake(raw.id(),raw.name(),raw.authored(),level,depth,area,bank,x,z,raw.polygon());
    }
  }
  record RiverResult(String sourceLake,String terminalLake,String sourceRiver,String terminalRiver,
                     boolean sea,double mouth,double length,HydraulicShape.Signature shape,short[] profile,List<River.Gate> gates) {
    River restore(String name,String sl,String tl,String sr,String tr,boolean ocean,double distance,List<River.Point> points) {
      if(!sourceLake.equals(sl)||!terminalLake.equals(tl)||!sourceRiver.equals(sr)||!terminalRiver.equals(tr)
          ||sea!=ocean||!HydraulicShape.close(length,distance)||!shape.matches(points))
        throw new IllegalStateException("Stale precomputed hydrology: "+name+"; rebuild release data");
      return new River(name,sl,tl,sr,tr,mouth,length,sea,points,profile,gates);
    }
  }
  final Map<String,LakeResult> lakes=new HashMap<>();
  final Map<String,RiverResult> rivers=new HashMap<>();
  final List<String> diagnostics=new ArrayList<>();

  static PrecomputedHydrology load() {
    try(var raw=PrecomputedHydrology.class.getResourceAsStream(RESOURCE)) {
      if(raw==null)throw new IOException("Missing precomputed hydraulic data");
      PrecomputedHydrology result=new PrecomputedHydrology();
      try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
        if(in.readInt()!=0x504a5248||in.readInt()!=3||in.readDouble()!=Geo.SCALE)throw new IOException("Invalid hydraulic data version");
        for(String input:INPUTS)if(!Arrays.equals(in.readNBytes(32),hashInput(input)))throw new IOException("Stale hydraulic input: "+input);
        int count=count(in,1,4096);
        for(int i=0;i<count;i++) {
          String id=in.readUTF();
          var lake=new LakeResult(in.readInt(),in.readInt(),in.readDouble(),in.readDouble(),in.readInt(),in.readInt());
          if(result.lakes.put(id,lake)!=null)throw new IOException("Duplicate baked lake");
        }
        count=count(in,1,4096);
        for(int i=0;i<count;i++) {
          String name=in.readUTF(),sl=in.readUTF(),tl=in.readUTF(),sr=in.readUTF(),tr=in.readUTF();
          boolean sea=in.readBoolean();double mouth=in.readDouble(),length=in.readDouble();
          HydraulicShape.Signature shape=HydraulicShape.read(in);
          short[] profile=new short[count(in,2,2_000_000)];
          int at=0;short value=in.readShort();
          while(at<profile.length) {
            int run=runLength(in);
            if(run<1||run>profile.length-at)throw new IOException("Invalid hydraulic run");
            Arrays.fill(profile,at,at+run,value);at+=run;
            value--;
          }
          int gates=count(in,0,10000);List<River.Gate> list=new ArrayList<>(gates);
          for(int g=0;g<gates;g++)list.add(new River.Gate(in.readUTF(),in.readUTF(),in.readDouble(),in.readDouble(),in.readInt()));
          if(result.rivers.put(name,new RiverResult(sl,tl,sr,tr,sea,mouth,length,shape,profile,List.copyOf(list)))!=null)
            throw new IOException("Duplicate baked river");
        }
        count=count(in,0,100000);for(int i=0;i<count;i++)result.diagnostics.add(in.readUTF());
        if(in.read()!=-1)throw new IOException("Trailing hydraulic data");
      }
      return result;
    } catch(IOException e){throw new IllegalStateException("Cannot load offline hydraulic model",e);}
  }
  private static int count(DataInputStream in,int min,int max)throws IOException {
    int n=in.readInt();if(n<min||n>max)throw new IOException("Invalid hydraulic count");return n;
  }
  private static int runLength(DataInputStream in)throws IOException {
    int value=0;
    for(int shift=0;shift<=21;shift+=7){int b=in.readUnsignedByte();value|=(b&127)<<shift;if((b&128)==0)return value;}
    throw new IOException("Hydraulic run overflow");
  }
  static byte[] hashInput(String name)throws IOException {
    try(var in=PrecomputedHydrology.class.getResourceAsStream("/assets/pjr/world/"+name)) {
      if(in==null)throw new IOException("Missing hydraulic source "+name);
      MessageDigest digest=digest();byte[] bytes=new byte[8192];int n;
      while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);return digest.digest();
    }
  }
  static byte[] hashPoints(List<River.Point> points) {
    MessageDigest digest=digest();byte[] bytes=new byte[32];
    for(var p:points) {
      double[] values={p.x(),p.z(),p.station(),p.width()};
      for(int k=0;k<4;k++){long v=Double.doubleToLongBits(values[k]);for(int b=0;b<8;b++)bytes[k*8+b]=(byte)(v>>>(56-8*b));}
      digest.update(bytes);
    }
    return digest.digest();
  }
  private static MessageDigest digest() {
    try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
  }
}
