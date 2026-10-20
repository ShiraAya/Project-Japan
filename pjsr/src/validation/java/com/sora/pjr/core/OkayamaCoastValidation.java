package com.sora.pjr.core;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
/** Exhaustive classification regression over every changed atlas tile. */
public final class OkayamaCoastValidation {
  static short[] decode(InputStream raw)throws Exception {
    try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {return CoastTileCodec.decode(in);}
  }
  public static void main(String[] args)throws Exception {
    long values=0,changed=0,dry=0,inland=0;int count=0;final int N=512*512;
    try(var old=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},null)) {
      var cls=old.loadClass("com.sora.pjr.core.PackedAtlas");var ctor=cls.getDeclaredConstructor(String.class);ctor.setAccessible(true);
      Object previous=ctor.newInstance("urban-coast");var open=cls.getDeclaredMethod("open",int.class,int.class,int.class);open.setAccessible(true);
      PackedAtlas current=new PackedAtlas("urban-coast");
      for(String line:Files.readAllLines(Path.of(args[1]))) {
        String[] f=line.split("\t");int x=Integer.parseInt(f[0]),y=Integer.parseInt(f[1]);
        short[] a=decode((InputStream)open.invoke(previous,14,x,y)),b=decode(current.open(14,x,y));
        for(int i=0;i<N;i++) {
          if((a[N+i]<0)!=(b[N+i]<0))throw new AssertionError("Water union changed "+x+","+y+":"+i);
          if(a[N+i]>=0) {dry++;if(b[i]<0)throw new AssertionError("Real land removed");}
          if(a[i]<0&&b[i]>=0)throw new AssertionError("Ocean became land");
          if(a[i]>=0&&b[i]<0) {if(a[N+i]>=0)throw new AssertionError("Non-water reclassified");changed++;}
          else if(b[N+i]<0&&b[i]>=0)inland++;
          values++;
        }
        count++;
      }
    }
    if(changed!=1587206)throw new AssertionError("Unexpected reclassification count "+changed);
    WorldEngine e=WorldEngine.load();var t=e.hydrology().terrain();var g=e.geometry(-257820,62406);
    if(t.sample(-257820,62406).land()||g.kind()!=Hydrology.Kind.OCEAN||!g.wet())throw new AssertionError("Screenshot centre still dry");
    System.out.printf("OKAYAMA COAST PASS%nchangedTiles=%d%ncheckedPixels=%d%nreclassifiedOcean=%d%npreservedDryPixels=%d%npreservedOtherInlandWaterPixels=%d%nscreenshotCentre=%s%n",count,values,changed,dry,inland,g);
  }
}
