package com.sora.pjr.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/** Offline sparse data with bounded shared decompression; no network or whole-country allocation. */
final class TiledRaster {
  private static final short[] DISTANCES=new short[256];
  static { for(int c=-128;c<128;c++)DISTANCES[c+128]=(short)Math.round(Math.copySign(Math.min(32760,4*Math.expm1(Math.abs(c)*Math.log(2)/9)),c)); }
  private final String folder;
  private final int size, channels, limit;
  private boolean compactDem,compactCoast;
  private int size12,size14,quantum12,quantum14;
  private PackedAtlas packed;
  private final Set<Long> available = new HashSet<>();
  private final ConcurrentHashMap<Long, short[]> cache = new ConcurrentHashMap<>();
  private final ArrayDeque<Long> order = new ArrayDeque<>();
  private static final class Last { long key=Long.MIN_VALUE; short[] data; }
  private final ThreadLocal<Last> last = ThreadLocal.withInitial(Last::new);

  TiledRaster(String folder,int size,int channels,int limit) {
    this.folder=folder;this.size=size;this.channels=channels;this.limit=limit;
    size12=size14=size;
    try(var raw=TiledRaster.class.getResourceAsStream("/assets/pjr/terrain/"+folder+"/format.properties")) {
      if(raw!=null) {
        Properties format=new Properties();format.load(raw);String codec=format.getProperty("codec","");
        compactDem=codec.equals("compact-dem-v1");compactCoast=codec.equals("procedural-coast-v1");
        if(!compactDem&&!compactCoast||compactDem&&channels!=1||compactCoast&&channels!=2)throw new IOException("Atlas codec mismatch");
        if(compactDem) {
          size12=Integer.parseInt(format.getProperty("size12"));size14=Integer.parseInt(format.getProperty("size14"));
          quantum12=Integer.parseInt(format.getProperty("quantum12"));quantum14=Integer.parseInt(format.getProperty("quantum14"));
          for(int n:new int[]{size12,size14})if(n<32||n>256||(n&(n-1))!=0)throw new IOException("Invalid DEM resolution");
          for(int q:new int[]{quantum12,quantum14})if(q<1||q>8||(q&(q-1))!=0)throw new IOException("Invalid DEM quantum");
        }
        if(format.getProperty("packSize","").equals("4"))packed=new PackedAtlas(folder);
      }
    } catch(IOException|NumberFormatException e){throw new IllegalStateException("Cannot load atlas format",e);}
    try(var in=TiledRaster.class.getResourceAsStream("/assets/pjr/terrain/"+folder+"/index.tsv")) {
      if(in==null)throw new IOException("Missing atlas index "+folder);
      try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
        for(String line;(line=reader.readLine())!=null;) {
          if(line.isBlank()||line.startsWith("#"))continue;
          String[] f=line.split("\\t");int z=f.length==3?Integer.parseInt(f[0]):14;
          int x=Integer.parseInt(f[f.length-2]),y=Integer.parseInt(f[f.length-1]);
          if(z<0||z>20||x<0||y<0||x>=(1<<z)||y>=(1<<z))throw new IOException("Invalid atlas coordinate");
          available.add(key(z,x,y));
        }
      }
    } catch(IOException e){throw new IllegalStateException("Cannot load terrain atlas",e);}
  }
  static long key(int z,int x,int y){return ((long)z<<56)|((long)x<<28)|(y&0xfffffffL);}
  boolean has(int z,int x,int y){return available.contains(key(z,x,y));}
  int tileCount(){return available.size();}
  int cachedCount(){return cache.size();}
  private short[] tile(int z,int x,int y) {
    long key=key(z,x,y);Last slot=last.get();
    if(slot.key==key)return slot.data;
    if(!available.contains(key)){slot.key=key;slot.data=null;return null;}
    short[] data=cache.get(key);
    if(data==null)synchronized(cache) {
      data=cache.get(key);
      if(data==null) {
        String name=(channels==1?z+"-":"")+x+"-"+y+".bin.gz";
        try(var raw=packed!=null?packed.open(z,x,y):TiledRaster.class.getResourceAsStream("/assets/pjr/terrain/"+folder+"/"+name)) {
          if(raw==null)throw new IOException("Missing indexed tile "+name);
          try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if(compactCoast)data=CoastTileCodec.decode(in);
            else if(compactDem)data=CompactDemCodec.decode(in,nativeSize(z),z==12?quantum12:quantum14);
            else {
            data=new short[size*size*channels];
            for(int i=0;i<data.length;i++)data[i]=channels==1?in.readShort():in.readByte();
            if(in.read()!=-1)throw new IOException("Trailing raster tile data");
            for(int channel=0;channel<channels;channel++)for(int row=0;row<size;row++)for(int col=0;col<size;col++) {
              int i=channel*size*size+row*size+col;
              int decoded=data[i]+(col>0?data[i-1]:0)+(row>0?data[i-size]:0)-(row>0&&col>0?data[i-size-1]:0);
              data[i]=channels==1?(short)decoded:(byte)decoded;
            }
            if(channels==2)for(int i=0;i<data.length;i++)data[i]=DISTANCES[data[i]+128];
            }
          }
        } catch(IOException e){throw new IllegalStateException("Cannot decode terrain atlas",e);}
        while(cache.size()>=limit)cache.remove(order.removeFirst());
        cache.put(key,data);order.addLast(key);
      }
    }
    slot.key=key;slot.data=data;return data;
  }
  private double value(int z,int px,int py,int channel) {
    int n=nativeSize(z);short[] a=tile(z,Math.floorDiv(px,n),Math.floorDiv(py,n));
    if(a==null)return Double.NaN;
    short v=a[channel*n*n+Math.floorMod(py,n)*n+Math.floorMod(px,n)];
    return v==Short.MIN_VALUE?Double.NaN:v;
  }
  double sample(int z,double px,double py,int channel) {
    return sample(z,px,py,channel,Double.NaN);
  }
  /** Missing DEM nodes contribute the coarser surface with their original weight.
   * Renormalizing a nearly-zero valid weight creates a cliff at its last node. */
  double sample(int z,double px,double py,int channel,double fallback) {
    if(compactDem){double step=(double)size/nativeSize(z);px/=step;py/=step;}
    int ix=(int)Math.floor(px),iy=(int)Math.floor(py);double fx=px-ix,fy=py-iy,sum=0,weight=0;
    for(int k=0;k<4;k++) {
      double v=value(z,ix+(k&1),iy+((k&2)==0?0:1),channel);
      if(!Double.isFinite(v))continue;
      double w=((k&1)==0?1-fx:fx)*((k&2)==0?1-fy:fy);sum+=v*w;weight+=w;
    }
    if(Double.isFinite(fallback) && weight<1-1e-12)
      return sum+(1-weight)*fallback;
    return weight>1e-6?sum/weight:Double.NaN;
  }
  private int nativeSize(int z){return compactDem?(z==12?size12:size14):size;}
  static double mercatorY(double lat){return (1-Math.log(Math.tan(Math.PI/4+Math.toRadians(lat)/2))/Math.PI)/2;}
}
